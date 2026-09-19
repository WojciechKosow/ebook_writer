package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.image.ImagePlan;
import com.ebookwriter.SaaS.entity.ContentSource;
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
    private final AssetPlacementService assetPlacementService;
    private final AssetUsageService assetUsageService;
    private final ChapterGenerationService chapterGenerationService;
    private final BookEditingService editingService;
    private final ImagePlanningService imagePlanningService;
    private final ImageGenerationService imageGenerationService;
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

            // Step 1.5 — decide how any user-uploaded assets should be used
            // (cover / specific chapter / unused). No-op when nothing was
            // uploaded; best-effort so it never fails the book.
            assetPlacementService.plan(ebookId);

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
                updateStatus(ebookId, EbookStatus.EDITING, 85);
                for (EbookChapter chapter : chapters) {
                    editingService.edit(ebookId, chapter.getId());
                }
            } else {
                log.info("Editorial pass disabled — skipping for ebook {}", ebookId);
            }

            // Step 3.5 — AI image pipeline. Planner decides what images add value
            // and where (structured plan); the generator creates them via OpenAI,
            // stores them in R2, and drops their inline tokens into the chapter
            // Markdown. Both are best-effort: image failures never fail the book,
            // and a book with no plan (or with images disabled) passes straight
            // through to rendering.
            updateStatus(ebookId, EbookStatus.PLANNING_IMAGES, 88);
            List<ImagePlan> imagePlan = imagePlanningService.plan(ebookId);
            if (!imagePlan.isEmpty()) {
                updateStatus(ebookId, EbookStatus.GENERATING_IMAGES, 90);
                imageGenerationService.generate(ebookId, imagePlan);
            }

            // Reconcile which offered assets the writer actually placed (and the
            // generated images just added), so the asset library shows accurate
            // per-chapter usage (metadata only — rendering reads the Markdown refs).
            assetUsageService.sync(ebookId, ContentSource.AI);

            // Step 4 — render PDF and learn the real page count. The reserved
            // page budget is a hard ceiling: an over-length book is trimmed to
            // fit rather than delivered (and billed) beyond what the user paid.
            updateStatus(ebookId, EbookStatus.RENDERING, 96);
            int pageBudget = ebookRepository.findById(ebookId).map(Ebook::getPageBudget).orElse(0);
            int actualPages = pdfGenerationService.renderAndStore(ebookId, pageBudget);

            // Step 5 — true up the up-front hold to what was actually produced:
            // charge for real pages, refund the unused reservation.
            reconcileCredits(ebookId, actualPages);

            updateStatus(ebookId, EbookStatus.COMPLETED, 100);
            log.info("Finished generation for ebook {}", ebookId);

        } catch (Exception e) {
            log.error("Generation failed for ebook {}", ebookId, e);
            fail(ebookId, e);
        }
    }

    /**
     * True up the reserved hold to the real page count. The final charge is the
     * actual number of pages, clamped to {@code [1, pageBudget]} — we never
     * charge more than the user reserved, and never nothing for a produced book.
     * Any unused reservation is refunded. Idempotent enough for the happy path:
     * it runs once, right after a successful render.
     */
    private void reconcileCredits(UUID ebookId, int actualPages) {
        ebookRepository.findById(ebookId).ifPresent(ebook -> {
            int budget = ebook.getPageBudget();
            int finalCharge = reconciledCharge(actualPages, budget);
            int refund = budget - finalCharge;

            ebook.setActualPageCount(actualPages);
            ebook.setCreditsCharged(finalCharge);
            ebookRepository.save(ebook);

            if (refund > 0) {
                ebookRepository.findUserIdById(ebookId).ifPresent(userId ->
                        creditService.refundUnusedHold(userId, refund, ebookId));
            }

            log.info("Reconciled ebook {}: {} pages rendered, reserved {}, charged {}, refunded {}",
                    ebookId, actualPages, budget, finalCharge, refund);
        });
    }

    /**
     * The credits a finished book is billed: the real page count, but never more
     * than the reserved budget (the user only authorised that much) and never
     * less than 1 (a produced book always costs at least one credit).
     */
    static int reconciledCharge(int actualPages, int budget) {
        return Math.max(1, Math.min(actualPages, budget));
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
