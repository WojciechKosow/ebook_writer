package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.credit.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full generation pipeline asynchronously:
 * <pre>
 *   PLANNING -> WRITING (per chapter) -> EDITING (per chapter) -> RENDERING -> COMPLETED
 * </pre>
 * Each step is a transactional call to a dedicated service, and results are
 * persisted as they are produced so a failure preserves everything written so
 * far. On any failure the ebook is moved to FAILED with the error recorded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EbookGenerationService {

    private static final int WRITING_START = 20;
    private static final int WRITING_SPAN = 60; // WRITING occupies 20% -> 80%

    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final BookPlanningService planningService;
    private final ChapterGenerationService chapterGenerationService;
    private final BookEditingService editingService;
    private final PdfGenerationService pdfGenerationService;
    private final AnthropicProperties anthropicProperties;
    private final CreditService creditService;

    @Async("ebookExecutor")
    public void generate(UUID ebookId) {
        log.info("Starting generation for ebook {}", ebookId);
        try {
            // Step 1 — plan
            updateStatus(ebookId, EbookStatus.PLANNING, 10);
            planningService.plan(ebookId);

            List<EbookChapter> chapters =
                    chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
            int total = chapters.size();

            // Step 2 — write chapters sequentially
            updateStatus(ebookId, EbookStatus.WRITING, WRITING_START);
            int done = 0;
            for (EbookChapter chapter : chapters) {
                chapterGenerationService.generate(ebookId, chapter.getId());
                done++;
                updateProgress(ebookId,
                        WRITING_START + (int) Math.round((double) WRITING_SPAN * done / total));
            }

            // Step 3 — editorial pass (optional; the most expensive step)
            if (anthropicProperties.isEditingEnabled()) {
                updateStatus(ebookId, EbookStatus.EDITING, 90);
                for (EbookChapter chapter : chapters) {
                    editingService.edit(ebookId, chapter.getId());
                }
            } else {
                log.info("Editorial pass disabled — skipping for ebook {}", ebookId);
            }

            // Step 4 — render PDF
            updateStatus(ebookId, EbookStatus.RENDERING, 95);
            pdfGenerationService.renderAndStore(ebookId);

            updateStatus(ebookId, EbookStatus.COMPLETED, 100);
            log.info("Finished generation for ebook {}", ebookId);

        } catch (Exception e) {
            log.error("Generation failed for ebook {}", ebookId, e);
            fail(ebookId, e);
        }
    }

    private void updateStatus(UUID ebookId, EbookStatus status, int progress) {
        ebookRepository.findById(ebookId).ifPresent(ebook -> {
            ebook.setStatus(status);
            ebook.setProgress(progress);
            ebookRepository.save(ebook);
        });
    }

    private void updateProgress(UUID ebookId, int progress) {
        ebookRepository.findById(ebookId).ifPresent(ebook -> {
            ebook.setProgress(progress);
            ebookRepository.save(ebook);
        });
    }

    private void fail(UUID ebookId, Exception e) {
        try {
            ebookRepository.findById(ebookId).ifPresent(ebook -> {
                ebook.setStatus(EbookStatus.FAILED);
                ebook.setErrorMessage(truncate(e.getMessage()));

                // Refund the credits charged for a generation that didn't finish.
                if (ebook.getCreditsCharged() > 0 && !ebook.isCreditsRefunded()) {
                    ebookRepository.findUserIdById(ebookId).ifPresent(userId -> {
                        creditService.refundGeneration(userId, ebook.getCreditsCharged(), ebookId);
                        ebook.setCreditsRefunded(true);
                    });
                }

                ebookRepository.save(ebook);
            });
        } catch (Exception inner) {
            log.error("Could not mark ebook {} as failed", ebookId, inner);
        }
    }

    private String truncate(String msg) {
        if (msg == null) return "Unknown error";
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
