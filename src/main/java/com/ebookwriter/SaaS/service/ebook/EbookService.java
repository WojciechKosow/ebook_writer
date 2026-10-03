package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.ChapterProgressDTO;
import com.ebookwriter.SaaS.dto.EbookContentResponse;
import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.request.ChapterUpdateRequest;
import com.ebookwriter.SaaS.request.EbookContentUpdateRequest;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.EbookRequest;
import com.ebookwriter.SaaS.dto.BookScopeResponse;
import com.ebookwriter.SaaS.dto.GenerationBudgetResponse;
import com.ebookwriter.SaaS.dto.ScopeEstimateDTO;
import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.service.credit.CreditService;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Application-facing entry point for the ebook feature. Owns creation +
 * kicking off async generation, status reads, and PDF retrieval, all scoped to
 * the owning user.
 */
@Service
@RequiredArgsConstructor
public class EbookService {

    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookPdfRepository pdfRepository;
    private final EbookGenerationService generationService;
    private final PdfGenerationService pdfGenerationService;
    private final AssetUsageService assetUsageService;
    private final CreditService creditService;
    private final ScopeEstimationService scopeEstimationService;
    private final BookBlueprintService blueprintService;

    /**
     * Create the ebook as a {@link EbookStatus#DRAFT}: the row exists so the user
     * can upload materials and assets to it, but no credits are held and nothing is
     * generated yet. Call {@link #start(UUID, UUID)} to reserve credits and begin.
     *
     * <p>The user chooses a {@link BookDepth}, never a page count: Scrivetta
     * determines the length from the topic, the materials and the depth.
     */
    public Ebook createDraft(User user, EbookRequest request) {
        Ebook ebook = Ebook.builder()
                .user(user)
                .topic(request.getTopic())
                .targetAudience(request.getTargetAudience())
                .bookGoal(request.getBookGoal())
                .style(request.getStyle())
                .depth(BookDepth.orDefault(request.getDepth()))
                .language(blankToEnglish(request.getLanguage()))
                .additionalInstructions(request.getAdditionalInstructions())
                .sourceMaterial(request.getSourceMaterial())
                .authorName(request.getAuthorName())
                .status(EbookStatus.DRAFT)
                .progress(0)
                .build();

        return ebookRepository.save(ebook);
    }

    /**
     * Change a draft's depth. The estimate (and the credits needed) follow; an
     * earlier plan that bounced for lack of credits no longer applies.
     */
    public BookScopeResponse updateDepth(UUID ebookId, UUID userId, BookDepth depth) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        if (ebook.getStatus() != EbookStatus.DRAFT) {
            throw new IllegalStateException("The depth can only be changed before generation starts.");
        }
        ebook.setDepth(BookDepth.orDefault(depth));
        ebook.setPlannedPages(null);
        ebook.setErrorMessage(null);
        ebookRepository.save(ebook);
        return scope(ebook, creditService.getBalance(userId));
    }

    /**
     * The draft's scope: Scrivetta's length and credit estimate for the selected
     * depth (and the other depths), from the brief, the materials and the
     * blueprint, plus whether the user can start.
     */
    @Transactional(readOnly = true)
    public BookScopeResponse getScope(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        return scope(ebook, creditService.getBalance(userId));
    }

    private BookScopeResponse scope(Ebook ebook, int balance) {
        List<ScopeEstimate> all = scopeEstimationService.estimateAllDepths(ebook);
        ScopeEstimate selected = all.stream().filter(e -> e.depth() == ebook.effectiveDepth()).findFirst()
                .orElseGet(() -> scopeEstimationService.estimate(ebook));
        int required = requiredCredits(ebook, selected);
        return new BookScopeResponse(ebook.effectiveDepth(), ScopeEstimateDTO.from(selected),
                all.stream().map(ScopeEstimateDTO::from).toList(), balance, required, balance >= required,
                ebook.getStatus() == EbookStatus.DRAFT ? ebook.getPlannedPages() : null,
                scopeEstimationService.maxPages());
    }

    /**
     * Credits needed to start: enough for the high end of the estimate, so the
     * expected book is never wound down early for lack of credits — or, if an
     * earlier attempt already planned the book larger than that, the plan's size.
     */
    static int requiredCredits(Ebook ebook, ScopeEstimate estimate) {
        int required = estimate.requiredCredits();
        if (ebook.getStatus() == EbookStatus.DRAFT && ebook.getPlannedPages() != null) {
            required = Math.max(required, ebook.getPlannedPages());
        }
        return Math.max(1, required);
    }

    /**
     * Reserve the generation credit hold and start generating a draft in the
     * background. Only a {@link EbookStatus#DRAFT} may be started; starting an
     * already-started book is a {@link IllegalStateException} (→ 409).
     *
     * <p><b>Scope first, then credits.</b> Scrivetta estimates the book's length
     * from the selected depth, the brief and the materials ({@link ScopeEstimationService}).
     * The user must hold enough credits for the <em>high end</em> of that estimate
     * (1 credit ≈ 1 page) — otherwise this throws {@link InsufficientCreditsException}
     * (→ 402) explaining how many credits the book needs. A book is never planned
     * smaller to fit the balance: the user is told instead.
     *
     * <p><b>Billing is final-page-count based</b>: we reserve a ceiling —
     * {@code min(balance, maxGenerationBudget) + maxOverdraft} — as an up-front hold
     * (see {@link CreditService#reserveGenerationHold}); once the PDF is rendered the
     * hold is trued up to the real page count and the unused credits are returned
     * (see {@link EbookGenerationService}).
     *
     * <p>The DRAFT → PENDING transition is claimed atomically, so concurrent or
     * duplicated start requests (double click, retry, refresh) can never both
     * reserve a hold. If a concurrent request left too little, the ebook is
     * returned to a DRAFT (its uploaded assets are kept) so the user can top up
     * and retry.
     */
    public Ebook start(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));

        if (ebook.getStatus() != EbookStatus.DRAFT) {
            throw new IllegalStateException("Ebook has already been started");
        }

        // Knowledge-based books are written from the author's BLUEPRINT_READY
        // blueprint; a book with materials or a blueprint that isn't finished must not
        // fall back to the brief-only flow and silently ignore the author's knowledge.
        BookBlueprintService.GenerationReadiness readiness = blueprintService.readiness(ebookId);
        if (readiness.blocked()) {
            throw new IllegalStateException(readiness.blockedReason());
        }

        // Analyse the scope before anything is reserved: depth + brief + materials.
        ScopeEstimate estimate = scopeEstimationService.estimate(ebook);
        int required = requiredCredits(ebook, estimate);

        // Friendly early gate before we mutate any state. The authoritative,
        // race-safe check is in reserveGenerationHold below (same wallet lock).
        int balance = creditService.getBalance(userId);
        if (balance < required) {
            throw notEnoughCredits(ebook, estimate, required, balance);
        }

        // Atomic claim: exactly one caller wins DRAFT -> PENDING, so a retried or
        // duplicated start can never reserve a second hold for the same book.
        int claimed = ebookRepository.claimForStart(ebookId, EbookStatus.DRAFT, EbookStatus.PENDING);
        if (claimed == 0) {
            throw new IllegalStateException("Ebook has already been started");
        }
        ebook.setStatus(EbookStatus.PENDING);

        // The hold is a credit ceiling, not a length: min(balance, safety cap) +
        // overdraft. It is trued up to the real page count after rendering.
        int pageBudget;
        try {
            pageBudget = creditService.reserveGenerationHold(userId, ebookId,
                    scopeEstimationService.maxPages(), required);
        } catch (InsufficientCreditsException e) {
            // Release the claim so the user can top up and retry (assets kept).
            ebook.setStatus(EbookStatus.DRAFT);
            ebook.setPageBudget(0);
            ebook.setCreditsCharged(0);
            ebookRepository.save(ebook);
            throw notEnoughCredits(ebook, estimate, required, e.getAvailable());
        }

        ebook.setPageBudget(pageBudget);
        ebook.setCreditsCharged(pageBudget);
        ebook.setCreditsRefunded(false);
        ebook.setErrorMessage(null);
        ebook.setEstimatedPagesLow(estimate.pagesLow());
        ebook.setEstimatedPagesHigh(estimate.pagesHigh());
        ebook.setPlannedPages(null);
        ebook.setGenerationMode(readiness.mode());
        ebook = ebookRepository.save(ebook);

        // Credits committed; safe to hand off to the async worker.
        generationService.generate(ebook.getId());
        return ebook;
    }

    private static InsufficientCreditsException notEnoughCredits(Ebook ebook, ScopeEstimate estimate,
                                                                 int required, int balance) {
        String why = ebook.getPlannedPages() != null && ebook.getPlannedPages() >= required
                ? "Scrivetta planned this book at about %d pages".formatted(ebook.getPlannedPages())
                : "At %s depth, Scrivetta estimates this book at about %d–%d pages"
                        .formatted(estimate.depth().label(), estimate.pagesLow(), estimate.pagesHigh());
        return new InsufficientCreditsException(
                "%s, so it needs up to %d credits to generate — you have %d. Add credits or choose a lighter depth."
                        .formatted(why, required, balance), required, balance);
    }

    /**
     * Resume a generation that FAILED part-way (e.g. one chapter could not be
     * written): chapters already written are kept and only the missing ones are
     * written, then the book is finished as usual. The failed run refunded its
     * whole hold, so a new hold is reserved like {@link #start} does (the minimum is
     * the planned book's size; same ceiling and overdraft rule) and trued up after
     * rendering.
     * Throws {@link IllegalStateException} (→ 409) when the book is not resumable.
     */
    public Ebook resume(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        if (!isResumable(ebook, chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))) {
            throw new IllegalStateException("This ebook cannot be resumed.");
        }
        if (ebook.isKnowledgeBased() && blueprintService.getGenerationInput(ebookId).isEmpty()) {
            throw new IllegalStateException("The book's knowledge or blueprint changed; it can't be resumed.");
        }
        // The whole book is billed at the end, so the new hold must cover the plan.
        int required = Math.max(1, ebook.getPlannedPages() != null ? ebook.getPlannedPages()
                : ebook.getEstimatedPagesHigh() != null ? ebook.getEstimatedPagesHigh() : 1);
        int balance = creditService.getBalance(userId);
        if (balance < required) {
            throw new InsufficientCreditsException(("This book is planned at about %d pages, so resuming needs "
                    + "up to %d credits — you have %d.").formatted(required, required, balance), required, balance);
        }
        if (ebookRepository.claimStatus(ebookId, EbookStatus.FAILED, EbookStatus.PENDING) == 0) {
            throw new IllegalStateException("This ebook cannot be resumed.");
        }
        int pageBudget;
        try {
            pageBudget = creditService.reserveGenerationHold(userId, ebookId,
                    scopeEstimationService.maxPages(), required);
        } catch (InsufficientCreditsException e) {
            ebookRepository.claimStatus(ebookId, EbookStatus.PENDING, EbookStatus.FAILED);
            throw e;
        }
        ebook.setStatus(EbookStatus.PENDING);
        ebook.setErrorMessage(null);
        ebook.setPageBudget(pageBudget);
        ebook.setCreditsCharged(pageBudget);
        ebook.setCreditsRefunded(false);
        ebook = ebookRepository.save(ebook);
        generationService.resume(ebook.getId());
        return ebook;
    }

    /**
     * A FAILED book whose billing was never settled and that has a plan with at
     * least one chapter already written can be resumed.
     */
    public static boolean isResumable(Ebook ebook, List<EbookChapter> chapters) {
        return ebook.getStatus() == EbookStatus.FAILED
                && !ebook.isCreditsReconciled()
                && chapters.stream().anyMatch(c -> c.getStatus() == ChapterStatus.WRITTEN
                || c.getStatus() == ChapterStatus.EDITED);
    }

    /**
     * Convenience one-shot used where assets aren't uploaded first: create the
     * draft and immediately start it. The HTTP API uses the two-step
     * create-draft → start flow instead.
     */
    public Ebook createAndStart(User user, EbookRequest request) {
        Ebook draft = createDraft(user, request);
        return start(draft.getId(), user.getId());
    }

    /**
     * What the creation form shows before a draft exists: each depth with a
     * preliminary, brief-based length and credit estimate, and the balance. The
     * estimate is refined once materials are added ({@link #getScope}).
     *
     * @param briefChars  length of the brief typed so far (topic, goal, instructions)
     * @param sourceChars length of any pasted source text
     */
    @Transactional(readOnly = true)
    public GenerationBudgetResponse getGenerationBudget(UUID userId, int briefChars, long sourceChars) {
        int balance = creditService.getBalance(userId);
        List<ScopeEstimateDTO> options = java.util.Arrays.stream(BookDepth.values())
                .map(d -> ScopeEstimateDTO.from(scopeEstimationService.estimateBrief(d,
                        Math.max(0, briefChars), Math.max(0, sourceChars))))
                .toList();
        return new GenerationBudgetResponse(balance, BookDepth.STANDARD, options, scopeEstimationService.maxPages());
    }

    @Transactional(readOnly = true)
    public EbookStatusResponse getStatus(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        List<EbookChapter> rows = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        List<ChapterProgressDTO> chapters = rows.stream().map(ChapterProgressDTO::from).toList();
        return EbookStatusResponse.from(ebook, chapters, isResumable(ebook, rows));
    }

    /**
     * Load the full editable manuscript (book metadata + every chapter's
     * Markdown body) for the text editor. Ownership-scoped.
     */
    @Transactional(readOnly = true)
    public EbookContentResponse getContent(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        // Chapters deferred to end the book within the user's credits are not part
        // of the book, so the editor never sees them as empty chapters.
        List<EbookChapter> chapters = ManuscriptContext.inBook(
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId));
        return EbookContentResponse.from(ebook, chapters);
    }

    /**
     * Replace the book's chapters with the editor's authoritative list, then
     * re-render the PDF so the download stays in sync. This one save covers
     * every chapter operation:
     * <ul>
     *   <li><b>edit</b> — an entry with an existing {@code id} updates that
     *       chapter's title/body;</li>
     *   <li><b>add</b> — an entry with a {@code null} id creates a new chapter;</li>
     *   <li><b>remove</b> — an existing chapter whose id is absent from the list
     *       is deleted;</li>
     *   <li><b>reorder</b> — each chapter's position in the list becomes its new
     *       chapter number.</li>
     * </ul>
     *
     * <p>Only a COMPLETED book may be edited — editing one that is still
     * generating would race with the generation pipeline. Re-rendering is
     * deliberately uncapped (no page-budget trim): these are the user's own
     * edits, not model output, so we deliver exactly what they wrote. Editing is
     * free — no credits are charged or refunded.
     */
    @Transactional
    public EbookContentResponse updateContent(UUID ebookId, UUID userId,
                                              EbookContentUpdateRequest request) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));

        if (ebook.getStatus() != EbookStatus.COMPLETED) {
            throw new IllegalStateException("Ebook is not ready to edit");
        }

        List<EbookChapter> existing =
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        Map<UUID, EbookChapter> byId = existing.stream()
                .collect(Collectors.toMap(EbookChapter::getId, Function.identity()));

        Set<UUID> keptIds = new HashSet<>();
        List<EbookChapter> ordered = new ArrayList<>();

        int number = 1;
        for (ChapterUpdateRequest edit : request.getChapters()) {
            EbookChapter chapter;
            if (edit.getId() != null) {
                chapter = byId.get(edit.getId());
                if (chapter == null) {
                    throw new IllegalArgumentException("Chapter not found: " + edit.getId());
                }
                if (!keptIds.add(chapter.getId())) {
                    throw new IllegalArgumentException("Duplicate chapter: " + edit.getId());
                }
            } else {
                chapter = EbookChapter.builder()
                        .ebook(ebook)
                        .status(ChapterStatus.EDITED)
                        .build();
            }
            chapter.setChapterNumber(number++);
            chapter.setTitle(edit.getTitle());
            chapter.setContent(edit.getContent());
            // These are the user's own edits: mark them so a future regeneration
            // can preserve them rather than overwrite manual work.
            chapter.setContentSource(ContentSource.USER);
            ordered.add(chapter);
        }

        // Delete chapters the user removed (present in the DB, absent from the save).
        List<EbookChapter> removed = existing.stream()
                .filter(c -> !keptIds.contains(c.getId()))
                .toList();
        if (!removed.isEmpty()) {
            chapterRepository.deleteAll(removed);
        }

        List<EbookChapter> saved = chapterRepository.saveAll(ordered);

        // Reconcile asset usage against the edited Markdown (images moved between
        // chapters, inserted, or removed) so the library's placement info stays
        // accurate and attributed to the user.
        assetUsageService.sync(ebookId, ContentSource.USER);

        // Re-render the PDF from the new manuscript (uncapped: keep exactly what
        // the user wrote). renderAndStore re-reads the just-saved chapters.
        pdfGenerationService.renderAndStore(ebookId, 0);

        return EbookContentResponse.from(ebook, saved);
    }

    @Transactional(readOnly = true)
    public List<EbookStatusResponse> list(UUID userId) {
        return ebookRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> EbookStatusResponse.from(e, List.of()))
                .toList();
    }

    /** Throw unless the user owns this ebook and its PDF is ready to download. */
    @Transactional(readOnly = true)
    public void requireDownloadable(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        if (ebook.getStatus() != EbookStatus.COMPLETED || !pdfRepository.existsById(ebookId)) {
            throw new IllegalStateException("Ebook is not ready for download");
        }
    }

    /** Load an ebook's PDF for download; enforces ownership and completion. */
    @Transactional(readOnly = true)
    public PdfDownload getPdf(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));

        if (ebook.getStatus() != EbookStatus.COMPLETED) {
            throw new IllegalStateException("Ebook is not ready for download");
        }

        EbookPdf pdf = pdfRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalStateException("Ebook is not ready for download"));

        String filename = safeFilename(ebook) + ".pdf";
        return new PdfDownload(pdf.getData(), filename);
    }

    private String safeFilename(Ebook ebook) {
        String base = (ebook.getTitle() != null && !ebook.getTitle().isBlank())
                ? ebook.getTitle() : "ebook";
        String cleaned = base.replaceAll("[^a-zA-Z0-9-_ ]", "").trim().replaceAll("\\s+", "_");
        return cleaned.isBlank() ? "ebook" : cleaned;
    }

    private String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }

    /** Simple carrier for a downloadable PDF. */
    public record PdfDownload(byte[] bytes, String filename) {
    }
}
