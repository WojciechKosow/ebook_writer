package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.image.ImagePlan;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.credit.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the full generation pipeline asynchronously:
 * <pre>
 *   PLANNING -> WRITING (per chapter) -> EDITING (per chapter) -> RENDERING -> COMPLETED
 * </pre>
 * Each step is a transactional call to a dedicated service, and results are
 * persisted as they are produced so a failure preserves everything written so
 * far. On any failure the ebook is moved to FAILED with the error recorded.
 *
 * <p><b>Two flows, one pipeline.</b> Legacy books are planned by Claude from the
 * brief ({@link BookPlanningService}); knowledge-based books take their chapters
 * from the author's approved blueprint ({@link KnowledgeBookPlanner}, no Claude
 * call) and each chapter is written from the author's knowledge (see
 * {@link ChapterGenerationService}). Everything after planning — credit pacing,
 * editing, images, cover, rendering, validation, billing — is shared.
 *
 * <p><b>Failure isolation.</b> A chapter that fails (after the client's own
 * retries) is marked FAILED and the book continues; failed chapters get one more
 * attempt at the end. Only if one still fails is the book FAILED — with every
 * written chapter kept, so {@link #resume} can finish it later.
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
    private final EbookValidationService validationService;
    private final AnthropicProperties anthropicProperties;
    private final CreditService creditService;
    private final OpenAiProperties openAiProperties;
    private final KnowledgeBookPlanner knowledgeBookPlanner;
    private final EbookImageRepository imageRepository;

    @Async("ebookExecutor")
    public void generate(UUID ebookId) {
        run(ebookId, false);
    }

    /**
     * Continue a generation that failed part-way: keep the plan and every written
     * chapter, write the missing ones, then finish the book as usual.
     */
    @Async("ebookExecutor")
    public void resume(UUID ebookId) {
        run(ebookId, true);
    }

    private void run(UUID ebookId, boolean resume) {
        log.info("{} generation for ebook {}", resume ? "Resuming" : "Starting", ebookId);
        try {
            boolean knowledgeBased = ebookRepository.findById(ebookId).map(Ebook::isKnowledgeBased).orElse(false);
            boolean planned = resume && !chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).isEmpty();

            if (!planned) {
                // Step 1 — plan: from the author's blueprint, or (legacy) by Claude from the brief.
                updateStatus(ebookId, EbookStatus.PLANNING, 10);
                if (knowledgeBased) {
                    knowledgeBookPlanner.plan(ebookId);
                } else {
                    planningService.plan(ebookId);
                }

                // Step 1.5 — decide how any user-uploaded assets should be used
                // (cover / specific chapter / unused). No-op when nothing was
                // uploaded; best-effort so it never fails the book.
                assetPlacementService.plan(ebookId);
            } else {
                // A resumed run re-decides chapters that were deferred for credits.
                for (EbookChapter c : chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId)) {
                    if (c.getStatus() == ChapterStatus.DEFERRED) {
                        c.setStatus(ChapterStatus.PENDING);
                        chapterRepository.save(c);
                    }
                }
            }

            List<EbookChapter> chapters =
                    chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

            // Step 2 — write chapters sequentially. The depth and the content shaped
            // the plan; credits are only permission to continue, never a reason to
            // write more or less. The start was gated on the estimate and the plan
            // on the hold, so pacing rarely matters; if the writing still runs far
            // past what the credits cover, the book is wound down to its planned
            // ending (flagged as creditLimited and shown to the user), never cut off.
            updateStatus(ebookId, EbookStatus.WRITING, WRITING_START);
            writeChapters(ebookId, chapters);
            chapters = ManuscriptContext.inBook(
                    chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId));

            // Step 3 — editorial pass (optional; the most expensive step)
            if (anthropicProperties.isEditingEnabled()) {
                updateStatus(ebookId, EbookStatus.EDITING, 85);
                for (EbookChapter chapter : chapters) {
                    if (chapter.getStatus() == ChapterStatus.EDITED) continue; // edited before a resume
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
            List<ImagePlan> imagePlan = resume && hasGeneratedIllustrations(ebookId)
                    ? List.of() : imagePlanningService.plan(ebookId);
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

            // Step 4 — render PDF and learn the real page count — a result, not a
            // goal: no estimate or plan size is enforced here. Only the credit
            // ceiling (min(balance, cap) + overdraft) bounds the render, because the
            // user can't be billed past it; writing is paced to stay inside it.
            updateStatus(ebookId, EbookStatus.RENDERING, 96);
            int pageBudget = ebookRepository.findById(ebookId).map(Ebook::getPageBudget).orElse(0);
            int actualPages = pdfGenerationService.renderAndStore(ebookId, pageBudget);

            // Step 4.5 — final quality gate. A genuinely broken render (no pages, no
            // content) raises EbookValidationException here, before any credits are
            // reconciled, so the book fails and the full hold is refunded rather than
            // a broken book being published as COMPLETED. Quality warnings are logged.
            validationService.validateOrThrow(ebookId, actualPages);

            // Step 5 — true up the up-front hold to the real page count: charge for
            // exactly the pages rendered (1 credit = 1 final page) and refund the
            // unused reservation. Idempotent, so a retry never charges twice.
            reconcileCredits(ebookId, actualPages);

            updateStatus(ebookId, EbookStatus.COMPLETED, 100);
            log.info("Finished generation for ebook {}", ebookId);

        } catch (PlanExceedsCreditsException e) {
            // The planned book is larger than the user's credits cover. Never cut it
            // down to fit: refund the hold and hand the draft back with the reason.
            returnToDraft(ebookId, e);
        } catch (Exception e) {
            log.error("Generation failed for ebook {}", ebookId, e);
            fail(ebookId, e);
        }
    }

    /**
     * Put a book whose plan needs more credits than the user holds back into
     * {@link EbookStatus#DRAFT}: the whole hold is refunded (nothing was written),
     * the planned size is kept so the draft shows how many credits it needs, and
     * the message explains what to do. Materials, blueprint and assets are kept.
     */
    private void returnToDraft(UUID ebookId, PlanExceedsCreditsException e) {
        log.info("Ebook {}: plan needs ~{} pages, more than the user's credits cover; back to draft",
                ebookId, e.getPlannedPages());
        try {
            // Planning is transactional, so a bounced plan saved no chapters; clear
            // any left from an earlier attempt all the same.
            List<EbookChapter> stale = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
            if (!stale.isEmpty()) {
                chapterRepository.deleteAll(stale);
            }
            ebookRepository.findById(ebookId).ifPresent(ebook -> {
                if (ebook.getCreditsCharged() > 0 && !ebook.isCreditsRefunded() && !ebook.isCreditsReconciled()) {
                    int held = ebook.getCreditsCharged();
                    ebookRepository.findUserIdById(ebookId).ifPresent(userId ->
                            creditService.refundGeneration(userId, held, ebookId));
                }
                ebook.setStatus(EbookStatus.DRAFT);
                ebook.setProgress(0);
                ebook.setPageBudget(0);
                ebook.setCreditsCharged(0);
                ebook.setCreditsRefunded(false);
                ebook.setGenerationMode(null);
                ebook.setPlannedPages(e.getPlannedPages());
                ebook.setErrorMessage(e.getMessage());
                ebookRepository.save(ebook);
            });
        } catch (Exception inner) {
            log.error("Could not return ebook {} to draft; failing it instead", ebookId, inner);
            fail(ebookId, e);
        }
    }

    /**
     * Write every planned chapter in order, asking {@link WritingBudget} before
     * each one whether the remaining credits still cover the rest of the plan:
     * <ul>
     *   <li>they do — the chapter is written at its planned depth (a book that runs
     *       past its estimate keeps going: the estimate never stops it);</li>
     *   <li>they nearly do — the remaining chapters are written a little tighter;</li>
     *   <li>they don't — the book is wound down: chapters that no longer fit are
     *       marked {@link ChapterStatus#DEFERRED} (kept in the outline, never
     *       rendered) and the planned final chapter is written as the ending, told
     *       which topics are out of scope so it never references them.</li>
     * </ul>
     * The decision is re-taken before every chapter from the words actually
     * written, so a chapter that ran long or short is accounted for.
     */
    private void writeChapters(UUID ebookId, List<EbookChapter> chapters) {
        int pageBudget = ebookRepository.findById(ebookId).map(Ebook::getPageBudget).orElse(0);
        int capacity = WritingBudget.capacityWords(pageBudget, chapters.size(), imageReservePages());

        Set<Integer> deferred = new HashSet<>();
        List<String> omittedTitles = new ArrayList<>();
        Map<EbookChapter, ChapterDirective> failed = new LinkedHashMap<>();
        int wordsWritten = 0;
        int done = 0;

        for (int i = 0; i < chapters.size(); i++) {
            EbookChapter chapter = chapters.get(i);
            if (deferred.contains(chapter.getChapterNumber())) {
                continue;
            }
            if (chapter.getStatus() == ChapterStatus.WRITTEN || chapter.getStatus() == ChapterStatus.EDITED) {
                // Already written by an earlier (resumed) run: keep it, count its length.
                wordsWritten += wordCount(chapter.getContent());
                done++;
                continue;
            }

            List<WritingBudget.PlannedWords> remaining = new ArrayList<>();
            for (EbookChapter c : chapters.subList(i, chapters.size())) {
                if (!deferred.contains(c.getChapterNumber())) {
                    remaining.add(new WritingBudget.PlannedWords(
                            c.getChapterNumber(), ChapterGenerationService.plannedWords(c)));
                }
            }
            WritingBudget.Decision decision = WritingBudget.decide(remaining, wordsWritten, capacity);

            if (decision.windDown()) {
                for (EbookChapter c : chapters) {
                    if (decision.isDeferred(c.getChapterNumber()) && deferred.add(c.getChapterNumber())) {
                        markDeferred(c);
                        omittedTitles.add(c.getTitle());
                    }
                }
                log.warn("Ebook {}: credits cover {} more words but the plan needs more; winding down — "
                                + "deferred chapters {} so the book ends at its planned conclusion",
                        ebookId, Math.max(0, capacity - wordsWritten), decision.deferred());
                markCreditLimited(ebookId);
                if (deferred.contains(chapter.getChapterNumber())) {
                    continue;
                }
            }

            int lastInBook = -1;
            for (WritingBudget.PlannedWords p : remaining) {
                if (!deferred.contains(p.chapterNumber())) {
                    lastInBook = p.chapterNumber();
                }
            }
            boolean isFinal = chapter.getChapterNumber() == lastInBook;
            ChapterDirective directive = new ChapterDirective(
                    decision.targetFor(chapter.getChapterNumber()),
                    decision.compressed(),
                    isFinal,
                    isFinal ? omittedTitles : List.of());

            if (!writeOrMarkFailed(ebookId, chapter, directive)) {
                failed.put(chapter, directive);
                continue;
            }
            wordsWritten += chapterRepository.findById(chapter.getId())
                    .map(c -> wordCount(c.getContent())).orElse(0);

            done++;
            int inBook = chapters.size() - deferred.size();
            updateProgress(ebookId,
                    WRITING_START + (int) Math.round((double) WRITING_SPAN * done / Math.max(1, inBook)));
        }
        // One more attempt for chapters that failed (a transient outage often passes).
        for (Map.Entry<EbookChapter, ChapterDirective> f : failed.entrySet()) {
            EbookChapter chapter = f.getKey();
            log.info("Retrying chapter {} of ebook {}", chapter.getChapterNumber(), ebookId);
            if (!writeOrMarkFailed(ebookId, chapter, f.getValue())) {
                throw new ChapterGenerationException("Chapter " + chapter.getChapterNumber() + " (\"" + chapter.getTitle()
                        + "\") could not be written. The chapters already written are kept — you can resume the generation.");
            }
            wordsWritten += chapterRepository.findById(chapter.getId()).map(c -> wordCount(c.getContent())).orElse(0);
            done++;
        }
        log.info("Wrote ebook {}: {} words across {} chapters ({} deferred, credit capacity {} words)",
                ebookId, wordsWritten, done, deferred.size(),
                capacity == Integer.MAX_VALUE ? "unbounded" : capacity);
    }

    /** Write one chapter; on failure mark it FAILED (keeping the reason) instead of failing the book. */
    private boolean writeOrMarkFailed(UUID ebookId, EbookChapter chapter, ChapterDirective directive) {
        try {
            chapterGenerationService.generate(ebookId, chapter.getId(), directive);
            return true;
        } catch (RuntimeException e) {
            log.warn("Chapter {} of ebook {} failed: {}", chapter.getChapterNumber(), ebookId, e.getMessage());
            chapterRepository.findById(chapter.getId()).ifPresent(c -> {
                c.setStatus(ChapterStatus.FAILED);
                c.setGenerationError(truncate(e.getMessage()));
                chapterRepository.save(c);
            });
            return false;
        }
    }

    /** Thrown when a chapter still fails after its retry; the book fails but stays resumable. */
    static class ChapterGenerationException extends RuntimeException {
        ChapterGenerationException(String message) {
            super(message);
        }
    }

    private boolean hasGeneratedIllustrations(UUID ebookId) {
        return imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId).stream()
                .anyMatch(i -> i.getRole() == EbookImageRole.ILLUSTRATION && i.getPlacedBy() == ContentSource.AI);
    }

    /** Record that the book was brought to an early ending by the credits, so the user is told. */
    private void markCreditLimited(UUID ebookId) {
        ebookRepository.findById(ebookId).ifPresent(ebook -> {
            if (!ebook.isCreditLimited()) {
                ebook.setCreditLimited(true);
                ebookRepository.save(ebook);
            }
        });
    }

    private void markDeferred(EbookChapter chapter) {
        chapter.setStatus(ChapterStatus.DEFERRED);
        chapter.setContent(null);
        chapterRepository.save(chapter);
    }

    /**
     * Pages to hold back from the credit capacity for generated illustrations
     * (roughly half a page each), so images added after writing can't push the
     * render past the ceiling.
     */
    private int imageReservePages() {
        if (!openAiProperties.isEnabled() || !openAiProperties.isConfigured()) {
            return 0;
        }
        return (int) Math.ceil(Math.max(0, openAiProperties.getMaxImagesPerBook()) * 0.5);
    }

    private static int wordCount(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        return s.strip().split("\\s+").length;
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
     * (a produced book always costs at least one credit).
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
