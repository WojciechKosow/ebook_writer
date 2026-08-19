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

    /**
     * Charge credits and start generating in the background. 1 credit per
     * requested page. Throws {@link InsufficientCreditsException} (→ 402) if the
     * balance can't cover it; the credits are refunded automatically if
     * generation later fails.
     */
    public Ebook createAndStart(User user, EbookRequest request) {
        int requiredCredits = Math.max(1, request.getApproxPageCount());

        // Fast pre-check so we don't create a row when the user clearly can't pay.
        int balance = creditService.getBalance(user.getId());
        if (balance < requiredCredits) {
            throw new InsufficientCreditsException(requiredCredits, balance);
        }

        Ebook ebook = Ebook.builder()
                .user(user)
                .topic(request.getTopic())
                .targetAudience(request.getTargetAudience())
                .style(request.getStyle())
                .approxPageCount(request.getApproxPageCount())
                .language(blankToEnglish(request.getLanguage()))
                .additionalInstructions(request.getAdditionalInstructions())
                .sourceMaterial(request.getSourceMaterial())
                .status(EbookStatus.PENDING)
                .progress(0)
                .creditsCharged(requiredCredits)
                .build();

        ebook = ebookRepository.save(ebook);

        // Atomic deduct (race-safe). If a concurrent request drained the balance
        // between the pre-check and here, roll back by removing the row.
        try {
            creditService.spend(user.getId(), requiredCredits, CreditTransactionType.GENERATION,
                    ebook.getId(), "Ebook generation (" + requiredCredits + " pages)");
        } catch (InsufficientCreditsException e) {
            ebookRepository.delete(ebook);
            throw e;
        }

        // Credits committed; safe to hand off to the async worker.
        generationService.generate(ebook.getId());
        return ebook;
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
