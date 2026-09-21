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
    private final CoverGenerationService coverGenerationService;
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

            // Cover: the AI generates a text-free visual and Scrivetta composes the
            // editable cover (title/subtitle stay real text). Best-effort — a
            // failure leaves the safe typographic cover and never fails the book.
            updateStatus(ebookId, EbookStatus.GENERATING_IMAGES, 94);
            coverGenerationService.generateForBook(ebookId);

            // Reconcile which offered assets the writer actually placed (and the
            // generated images just added), so the asset library shows accurate
            // per-chapter usage (metadata only — rendering reads the Markdown refs).
            assetUsageService.sync(ebookId, ContentSource.AI);

            // Step 4 — render PDF and learn the real page count. The reserved
            // ceiling (target + allowed overdraft, capped so the balance can't
            // fall below -maxOverdraft) is a hard limit: a runaway book is trimmed
            // at a paragraph boundary to fit rather than driving the balance past
            // the overdraft floor. A natural ending a little over target renders
            // in full — the ceiling only bites the genuine overshoot.
            updateStatus(ebookId, EbookStatus.RENDERING, 96);
            int pageBudget = ebookRepository.findById(ebookId).map(Ebook::getPageBudget).orElse(0);
            int actualPages = pdfGenerationService.renderAndStore(ebookId, pageBudget);

            // Step 5 — true up the up-front hold to the real page count: charge for
            // exactly the pages rendered (1 credit = 1 final page) and refund the
            // unused reservation. Idempotent, so a retry never charges twice.
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
     * actual number of pages rendered (1 credit = 1 final page), clamped to
     * {@code [1, pageBudget]} — the ceiling was reserved so this never charges
     * past {@code -maxOverdraft}, and a produced book always costs at least one
     * credit. Any unused reservation is refunded.
     *
     * <p><b>Idempotent.</b> The true-up is claimed atomically via
     * {@link EbookRepository#markReconciled}; a second attempt (a retried worker,
     * a re-run generation) is a no-op, so credits are never charged twice for the
     * same ebook.
     */
    private void reconcileCredits(UUID ebookId, int actualPages) {
        if (ebookRepository.markReconciled(ebookId) == 0) {
            log.info("Ebook {} already reconciled — skipping duplicate billing", ebookId);
            return;
        }
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
     * The credits a finished book is billed: the real page count (1 credit =
     * 1 final page), never more than the reserved ceiling (which was sized so the
     * balance cannot fall below {@code -maxOverdraft}) and never less than 1
     * (a produced book always costs at least one credit). Because the ceiling is
     * {@code target + overdraft}, this charges the true length even when it
     * exceeds the requested target.
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

                // Refund the full up-front hold for a generation that didn't
                // finish: no final PDF means no real pages to bill, so the user
                // pays nothing. Skip once billing has already been reconciled (a
                // rendered, billed book) or already refunded, so we never refund
                // twice.
                if (ebook.getCreditsCharged() > 0
                        && !ebook.isCreditsRefunded()
                        && !ebook.isCreditsReconciled()) {
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
