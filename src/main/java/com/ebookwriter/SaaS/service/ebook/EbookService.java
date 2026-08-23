package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.ChapterProgressDTO;
import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
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

import java.util.List;
import java.util.UUID;

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
    public Ebook createAndStart(User user, EbookRequest request) {
        int requestedPages = Math.max(1, request.getApproxPageCount());

        // Fast pre-check so we don't create a row when the user clearly can't pay
        // for even the pages they asked for.
        int balance = creditService.getBalance(user.getId());
        if (balance < requestedPages) {
            throw new InsufficientCreditsException(requestedPages, balance);
        }

        int pageBudget = reservedBudget(requestedPages, balance);

        Ebook ebook = Ebook.builder()
                .user(user)
                .topic(request.getTopic())
                .targetAudience(request.getTargetAudience())
                .style(request.getStyle())
                .approxPageCount(requestedPages)
                .language(blankToEnglish(request.getLanguage()))
                .additionalInstructions(request.getAdditionalInstructions())
                .sourceMaterial(request.getSourceMaterial())
                .status(EbookStatus.PENDING)
                .progress(0)
                .pageBudget(pageBudget)
                .creditsCharged(pageBudget)
                .build();

        ebook = ebookRepository.save(ebook);

        // Atomic deduct (race-safe). If a concurrent request drained the balance
        // between the pre-check and here, roll back by removing the row.
        try {
            creditService.spend(user.getId(), pageBudget, CreditTransactionType.GENERATION,
                    ebook.getId(), "Ebook generation hold (up to " + pageBudget + " pages)");
        } catch (InsufficientCreditsException e) {
            ebookRepository.delete(ebook);
            throw e;
        }

        // Credits committed; safe to hand off to the async worker.
        generationService.generate(ebook.getId());
        return ebook;
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
