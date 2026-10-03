package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.plan.BookPlan;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.PlanningPrompts;
import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 1 — ask the model for a structured book plan and persist it as the
 * ebook's metadata plus a set of PENDING chapters.
 *
 * <p>The plan is driven by the selected {@link BookDepth} and the content, never
 * by a page count: the planner decides how many chapters the subject needs at
 * that depth and how much room each needs, and the sum is the planned length.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookPlanningService {

    private static final long PLAN_MAX_TOKENS = 4000L;

    // Own Jackson 2 mapper: Spring Boot 4 registers a Jackson 3 ObjectMapper by
    // default, so we don't rely on an injected bean for this lenient parse.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final ScopeEstimationService scopeEstimationService;

    @Transactional
    public void plan(UUID ebookId) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));

        // The user chose a DEPTH, not a page count. Scrivetta's estimate (from the
        // brief and any source text) is passed to the planner as orientation only;
        // the plan's length is whatever the content needs at that depth.
        BookDepth depth = ebook.effectiveDepth();
        ScopeEstimate estimate = scopeEstimationService.estimate(ebook);

        // A fixed structure promised in the brief ("7-day plan", "10-step guide")
        // must be delivered in full; tell the planner so and check it afterwards.
        Optional<StructureRequirement> structure = StructureRequirement.detect(ebook);
        String structureHint = structure
                .map(s -> "This book promises a " + s.describe() + " structure. Every one of "
                        + "the " + s.count() + " " + s.unit() + "s must be covered in the plan; "
                        + "do not stop partway.")
                .orElse("");

        String system = PlanningPrompts.system(ebook.getLanguage());
        String user = PlanningPrompts.user(ebook, depth, estimate, structureHint);

        String raw = anthropicService.complete(system, user, PLAN_MAX_TOKENS);
        BookPlan plan = parsePlan(raw);

        // Only two guards, neither derived from user input: a planner that ran away
        // far past any sensible scope, and the per-book safety maximum.
        List<PlannedChapter> planned = limitRunaway(plan.chapters(), estimate);
        planned = clampToBudget(planned, maxContentPages(depth));
        int plannedBook = totalPages(planned) + EbookHtmlBuilder.FRONT_MATTER_PAGES;
        // Whether the plan fits what the user agreed to (and their credits) is the
        // orchestrator's call: it pauses for the user's decision rather than cutting.

        ebook.setTitle(orDefault(plan.title(), ebook.getTopic()));
        ebook.setSubtitle(plan.subtitle());
        ebook.setDescription(plan.description());
        ebook.setWritingGuidelines(plan.writingGuidelines());
        ebook.setPlanJson(raw);
        ebook.setPlannedPages(plannedBook);

        ebook.getChapters().clear();
        int number = 1;
        for (PlannedChapter pc : planned) {
            EbookChapter chapter = EbookChapter.builder()
                    .ebook(ebook)
                    .chapterNumber(number++)
                    .title(orDefault(pc.title(), "Chapter " + (number - 1)))
                    .description(pc.description())
                    .approxPages(Math.max(1, pc.approxPages()))
                    .status(ChapterStatus.PENDING)
                    .build();
            ebook.getChapters().add(chapter);
        }

        ebookRepository.save(ebook);
        log.info("Planned ebook {} — '{}' at depth {}: {} chapters, ~{} pages "
                        + "(estimate was {}–{}, credit ceiling {})",
                ebookId, ebook.getTitle(), depth, ebook.getChapters().size(), plannedBook,
                estimate.pagesLow(), estimate.pagesHigh(), ebook.getPageBudget());

        // Structural completeness check: a promised fixed structure (N days/steps)
        // needs a chapter per unit (or grouped units). This never fails the book,
        // but a shortfall is a strong signal the planner skipped units.
        structure.ifPresent(req -> {
            if (ebook.getChapters().size() < Math.min(req.count(), 3)) {
                log.warn("Ebook {} promises a {} structure but the plan has only {} chapters; "
                                + "the finished book may not cover every {}.",
                        ebookId, req.describe(), ebook.getChapters().size(), req.unit());
            }
        });
    }

    /**
     * The most content pages a plan at this depth may have: the depth's cap and
     * the per-book safety maximum (system limits — never a user-chosen length).
     */
    int maxContentPages(BookDepth depth) {
        return Math.min(scopeEstimationService.maxPages(), ScopeEstimator.depthCap(depth))
                - EbookHtmlBuilder.FRONT_MATTER_PAGES;
    }

    static int totalPages(List<PlannedChapter> chapters) {
        return chapters.stream().mapToInt(pc -> Math.max(1, pc.approxPages())).sum();
    }

    /**
     * How far a plan may exceed Scrivetta's own estimate before it is treated as a
     * planner runaway. Generous on purpose: the estimate is orientation, and a plan
     * that needs more than its range is normal — only a plan several times larger
     * than anything the depth and material suggest is pulled back.
     */
    static final double RUNAWAY_FACTOR = 2.0;

    /** Where a runaway plan is pulled back to, relative to the estimate's high end. */
    static final double RUNAWAY_LANDING = 1.5;

    /**
     * Guard against a planner runaway (the "keeps expanding" failure mode), never
     * against a long book. A plan up to {@link #RUNAWAY_FACTOR}× the high end of
     * Scrivetta's estimate is the planner's own design and is returned unchanged —
     * the estimate is not a limit. Beyond that, chapter sizes are scaled down
     * toward {@link #RUNAWAY_LANDING}× the estimate. Chapters are never dropped
     * here, so every planned topic (and the conclusion) survives.
     */
    static List<PlannedChapter> limitRunaway(List<PlannedChapter> chapters, ScopeEstimate estimate) {
        int total = totalPages(chapters);
        int estimateContent = Math.max(1, estimate.pagesHigh() - EbookHtmlBuilder.FRONT_MATTER_PAGES);
        if (total <= estimateContent * RUNAWAY_FACTOR) {
            return chapters;
        }
        int landing = (int) Math.ceil(estimateContent * RUNAWAY_LANDING);
        double factor = (double) landing / total;
        List<PlannedChapter> scaled = new ArrayList<>(chapters.size());
        for (PlannedChapter pc : chapters) {
            int pages = Math.max(1, (int) Math.round(Math.max(1, pc.approxPages()) * factor));
            scaled.add(new PlannedChapter(pc.title(), pc.description(), pages));
        }
        log.warn("Planner runaway: {} content pages against an estimate of {}–{}; scaled toward {}",
                total, estimate.pagesLow(), estimate.pagesHigh(), landing);
        return scaled;
    }

    /**
     * Force the planned chapters to fit within {@code budget} pages — the per-book
     * safety maximum (never a user-chosen length). Each chapter keeps a floor of one page; if the model's totals
     * exceed the budget the per-chapter page counts are scaled down proportionally,
     * and if it planned more chapters than the budget can hold at one page each,
     * chapters are dropped from before the final one, so the book always keeps its
     * concluding chapter rather than losing its ending. Returns page counts that
     * sum to at most {@code budget}.
     */
    static List<PlannedChapter> clampToBudget(List<PlannedChapter> chapters, int budget) {
        // If even one page per chapter overflows the budget, keep only as many
        // chapters as the budget can afford — the leading ones plus the conclusion.
        List<PlannedChapter> kept;
        if (chapters.size() > budget) {
            kept = new ArrayList<>(chapters.subList(0, Math.max(0, budget - 1)));
            if (budget >= 1) {
                kept.add(chapters.get(chapters.size() - 1));
            }
        } else {
            kept = new ArrayList<>(chapters);
        }

        int total = kept.stream().mapToInt(pc -> Math.max(1, pc.approxPages())).sum();
        if (total <= budget) {
            return kept;
        }

        double factor = (double) budget / total;
        List<PlannedChapter> scaled = new ArrayList<>(kept.size());
        int running = 0;
        for (PlannedChapter pc : kept) {
            int pages = Math.max(1, (int) Math.floor(Math.max(1, pc.approxPages()) * factor));
            running += pages;
            scaled.add(new PlannedChapter(pc.title(), pc.description(), pages));
        }
        // Flooring can leave a few pages of slack under the budget; that is fine
        // (we round down, never up, so the ceiling is never breached).
        log.debug("Clamped plan from {} to {} pages (budget {})", total, running, budget);
        return scaled;
    }

    /** Extract the JSON object from the model output and parse it leniently. */
    private BookPlan parsePlan(String raw) {
        String json = extractJsonObject(raw);
        try {
            BookPlan plan = OBJECT_MAPPER.readValue(json, BookPlan.class);
            if (plan.chapters() == null || plan.chapters().isEmpty()) {
                throw new IllegalStateException("Plan contained no chapters");
            }
            return plan;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse book plan JSON: " + e.getMessage(), e);
        }
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new RuntimeException("Model did not return a JSON object for the plan");
        }
        return raw.substring(start, end + 1);
    }

    private String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
