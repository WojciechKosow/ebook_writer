package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.ChapterProgressDTO;
import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.EbookRequest;
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

    /** Persist the request and start generating in the background. */
    public Ebook createAndStart(User user, EbookRequest request) {
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
                .build();

        ebook = ebookRepository.save(ebook);

        // Row is committed by save(); safe to hand off to the async worker.
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
