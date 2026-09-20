package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.ChapterProgressDTO;
import com.ebookwriter.SaaS.dto.EbookContentResponse;
import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
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
import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.request.EbookRequest;
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
     * Reserve credits and start generating in the background. 1 credit per page.
     *
     * <p>We do <em>not</em> charge the raw requested count and hope the model
     * matches it. Instead we reserve a <b>page budget</b> — the requested count
     * plus a configurable tolerance, capped by what the user can actually pay —
     * as an up-front hold. That hold is the ceiling generation may reach, so we
     * never produce (and pay the model for) more pages than the user authorised.
     * Once the PDF is rendered the real page count is known and the hold is
     * trued up: the user is charged for exactly the pages produced and the
     * unused reservation is refunded (see {@link EbookGenerationService}).
     *
     * <p>Throws {@link InsufficientCreditsException} (→ 402) if the balance can't
     * cover the requested page count; the full hold is refunded automatically if
     * generation later fails.
     */
    /**
     * Create the ebook as a {@link EbookStatus#DRAFT}: the row exists so the user
     * can upload assets to it, but no credits are held and nothing is generated
     * yet. Call {@link #start(UUID, UUID)} to reserve credits and begin.
     */
    public Ebook createDraft(User user, EbookRequest request) {
        int requestedPages = Math.max(1, request.getApproxPageCount());

        Ebook ebook = Ebook.builder()
                .user(user)
                .topic(request.getTopic())
                .targetAudience(request.getTargetAudience())
                .style(request.getStyle())
                .approxPageCount(requestedPages)
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
     * Reserve the page-budget credit hold and start generating a draft in the
     * background. Only a {@link EbookStatus#DRAFT} may be started; starting an
     * already-started book is a {@link IllegalStateException} (→ 409).
     *
     * <p>Reservation is unchanged from before the draft split: reserve a page
     * budget (requested + tolerance, capped by balance) as an up-front hold that
     * caps generation, then true it up to the real page count on completion. If a
     * concurrent request drained the balance, the ebook is left as a DRAFT (its
     * uploaded assets are kept) rather than deleted.
     */
    public Ebook start(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));

        if (ebook.getStatus() != EbookStatus.DRAFT) {
            throw new IllegalStateException("Ebook has already been started");
        }

        int requestedPages = Math.max(1, ebook.getApproxPageCount());

        // Fast pre-check for a clear "can't pay for even the requested pages".
        int balance = creditService.getBalance(userId);
        if (balance < requestedPages) {
            throw new InsufficientCreditsException(requestedPages, balance);
        }

        int pageBudget = reservedBudget(requestedPages, balance);
        ebook.setPageBudget(pageBudget);
        ebook.setCreditsCharged(pageBudget);
        ebook.setStatus(EbookStatus.PENDING);
        ebook = ebookRepository.save(ebook);

        // Atomic deduct (race-safe). If a concurrent request drained the balance
        // between the pre-check and here, revert to a draft so the user can retry.
        try {
            creditService.spend(userId, pageBudget, CreditTransactionType.GENERATION,
                    ebook.getId(), "Ebook generation hold (up to " + pageBudget + " pages)");
        } catch (InsufficientCreditsException e) {
            ebook.setStatus(EbookStatus.DRAFT);
            ebook.setPageBudget(0);
            ebook.setCreditsCharged(0);
            ebookRepository.save(ebook);
            throw e;
        }

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
     * The page ceiling to reserve: requested pages plus tolerance head-room,
     * never more than the user can pay and never below the requested count.
     */
    private int reservedBudget(int requestedPages, int balance) {
        double tolerance = Math.max(0.0, creditProperties.getPageBudgetTolerance());
        int withHeadroom = requestedPages + (int) Math.ceil(requestedPages * tolerance);
        return Math.min(Math.max(withHeadroom, requestedPages), balance);
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
        List<EbookChapter> chapters =
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
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
