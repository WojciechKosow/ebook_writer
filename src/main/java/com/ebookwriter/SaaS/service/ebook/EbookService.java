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
import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.dto.GenerationBudgetResponse;
import com.ebookwriter.SaaS.service.credit.CreditService;
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
    private final CreditProperties creditProperties;

    /**
     * Create the ebook as a {@link EbookStatus#DRAFT}: the row exists so the user
     * can upload assets to it, but no credits are held and nothing is generated
     * yet. Call {@link #start(UUID, UUID)} to reserve credits and begin.
     *
     * <p>The user's selected target length (~20/30/50/75/100 pages) is stored in
     * {@code approxPageCount}. It is a <b>soft content budget</b>: it shapes the
     * plan so the book lands around that size, and is never a hard page limit.
     * When no target is selected the standard target is used.
     */
    public Ebook createDraft(User user, EbookRequest request) {
        int target = resolveTargetPages(request.getTargetPages());

        Ebook ebook = Ebook.builder()
                .user(user)
                .topic(request.getTopic())
                .targetAudience(request.getTargetAudience())
                .style(request.getStyle())
                .approxPageCount(target)
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
     * Normalise a selected target length: absent → the standard target; out of
     * range → clamped to {@code [ContentBudget.MIN_TARGET_PAGES, maxTargetPages]}.
     * Clamped rather than rejected, because the target is only a planning signal.
     */
    int resolveTargetPages(Integer requested) {
        int max = Math.max(ContentBudget.MIN_TARGET_PAGES, creditProperties.getMaxTargetPages());
        int fallback = Math.max(ContentBudget.MIN_TARGET_PAGES, creditProperties.getStandardTargetPages());
        if (requested == null || requested <= 0) {
            return Math.min(fallback, max);
        }
        return Math.max(ContentBudget.MIN_TARGET_PAGES, Math.min(requested, max));
    }

    /**
     * The balance needed to start a book with this target: the standard minimum
     * budget, but never more than the target itself — a user who asks for a short
     * ~20-page book only needs enough credits for that book.
     */
    int minimumToStart(int targetPages) {
        int minBudget = Math.max(1, creditProperties.getMinGenerationBudget());
        return Math.max(1, Math.min(minBudget, targetPages));
    }

    /**
     * Reserve the generation credit hold and start generating a draft in the
     * background. Only a {@link EbookStatus#DRAFT} may be started; starting an
     * already-started book is a {@link IllegalStateException} (→ 409).
     *
     * <p>Billing is <b>final-page-count based</b>: 1 credit = 1 rendered page, and
     * the length is a <em>result</em> of generation, never a size the user ordered.
     * Credits are the generation <b>budget</b>: a user must hold at least the
     * standard {@code minGenerationBudget} (e.g. 30 credits) — or the selected
     * target, if that is smaller — to begin. This is the smallest budget needed to
     * produce a complete book at that scale, not a promise of that many pages. We reserve a ceiling — {@code min(target,
     * balance) + maxOverdraft} — as an up-front hold (see
     * {@link CreditService#reserveGenerationHold}). That ceiling both caps how many
     * pages may be rendered and guarantees the balance can never fall below
     * {@code -maxOverdraft}, while still allowing the small overdraft that lets a
     * book wind down to a natural ending. Once the PDF is rendered the hold is
     * trued up to the real page count and the unused credits are returned (see
     * {@link EbookGenerationService}), so a book that naturally finishes early
     * leaves the rest of the budget on the account.
     *
     * <p>The DRAFT → PENDING transition is claimed atomically, so concurrent or
     * duplicated start requests (double click, retry, refresh) can never both
     * reserve a hold. Throws {@link InsufficientCreditsException} (→ 402) when the
     * balance is below the standard generation budget; if a concurrent request left
     * too little, the ebook is returned to a DRAFT (its uploaded assets are kept)
     * so the user can top up and retry.
     */
    public Ebook start(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));

        if (ebook.getStatus() != EbookStatus.DRAFT) {
            throw new IllegalStateException("Ebook has already been started");
        }

        int minBudget = minimumToStart(ebook.getApproxPageCount() > 0
                ? ebook.getApproxPageCount() : resolveTargetPages(null));

        // Friendly early gate before we mutate any state: a user below the standard
        // generation budget cannot start. The authoritative, race-safe check is in
        // reserveGenerationHold below (same wallet lock as the reservation).
        int balance = creditService.getBalance(userId);
        if (balance < minBudget) {
            throw new InsufficientCreditsException(minBudget, balance);
        }

        // Atomic claim: exactly one caller wins DRAFT -> PENDING, so a retried or
        // duplicated start can never reserve a second hold for the same book.
        int claimed = ebookRepository.claimForStart(ebookId, EbookStatus.DRAFT, EbookStatus.PENDING);
        if (claimed == 0) {
            throw new IllegalStateException("Ebook has already been started");
        }
        ebook.setStatus(EbookStatus.PENDING);

        // Two separate concepts: the TARGET (approxPageCount) shapes the plan; the
        // CEILING reserved here is what the user may actually generate. Reserve
        // against the absolute safety cap so the hold becomes
        // min(balance, maxGenerationBudget) + overdraft: a book that genuinely needs
        // to run past its soft target may continue while credits allow, and is only
        // wound down to a natural ending when it approaches the user's real budget.
        // Unused credits are refunded after render.
        int targetPages = Math.max(1, creditProperties.getMaxGenerationBudget());

        // Reserve the overdraft-aware hold under the wallet lock, gated on the
        // standard generation budget.
        int pageBudget;
        try {
            pageBudget = creditService.reserveGenerationHold(userId, ebookId, targetPages, minBudget);
        } catch (InsufficientCreditsException e) {
            // Release the claim so the user can top up and retry (assets kept).
            ebook.setStatus(EbookStatus.DRAFT);
            ebook.setPageBudget(0);
            ebook.setCreditsCharged(0);
            ebookRepository.save(ebook);
            throw e;
        }

        ebook.setPageBudget(pageBudget);
        ebook.setCreditsCharged(pageBudget);
        ebook = ebookRepository.save(ebook);

        // Credits committed; safe to hand off to the async worker.
        generationService.generate(ebook.getId());
        return ebook;
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
     * Describe the generation budget for the creation UI: how many credits are
     * needed to start, the orientational page range for a standard ebook, the
     * user's current balance, and whether they can generate now. The frontend uses
     * this to show "Estimated usage ~20–30 credits", "Your balance: N credits" and
     * either "Enough credits to generate this ebook" or "You need at least N
     * credits" — communicating a budget, never a guaranteed page count.
     */
    @Transactional(readOnly = true)
    public GenerationBudgetResponse getGenerationBudget(UUID userId) {
        int balance = creditService.getBalance(userId);
        int low = Math.max(1, creditProperties.getStandardTargetMinPages());
        int high = Math.max(low, creditProperties.getStandardTargetPages());
        int defaultTarget = resolveTargetPages(null);
        int minCredits = minimumToStart(defaultTarget);
        int maxTarget = Math.max(ContentBudget.MIN_TARGET_PAGES, creditProperties.getMaxTargetPages());
        int affordable = Math.max(0, Math.min(balance, creditProperties.getMaxGenerationBudget()));
        List<Integer> options = GenerationBudgetResponse.TARGET_OPTIONS.stream()
                .filter(t -> t <= maxTarget)
                .toList();
        return new GenerationBudgetResponse(minCredits, low, high, balance, balance >= minCredits,
                options, defaultTarget, maxTarget, affordable);
    }

    @Transactional(readOnly = true)
    public EbookStatusResponse getStatus(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        List<ChapterProgressDTO> chapters =
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).stream()
                        .map(ChapterProgressDTO::from)
                        .toList();
        return EbookStatusResponse.from(ebook, chapters);
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
