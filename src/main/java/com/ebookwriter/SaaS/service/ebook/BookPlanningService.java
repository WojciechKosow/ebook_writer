package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.plan.BookPlan;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.PlanningPrompts;
import com.ebookwriter.SaaS.config.properties.CreditProperties;
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
    private final CreditProperties creditProperties;

    @Transactional
    public void plan(UUID ebookId) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));

        int frontMatter = EbookHtmlBuilder.FRONT_MATTER_PAGES;

        // Two separate concepts (see ContentBudget):
        //  - the TARGET the user selected is a soft content budget that shapes the
        //    outline, so the book naturally lands around that size;
        //  - the reserved CEILING (min(balance, cap) + overdraft) is what the user
        //    may actually generate — a hard maximum, never something to fill.
        // Legacy rows without a reservation fall back to the target as the ceiling.
        int targetPages = ebook.getApproxPageCount() > 0 ? ebook.getApproxPageCount()
                : Math.max(ContentBudget.MIN_TARGET_PAGES, creditProperties.getStandardTargetPages());
        int pageBudget = ebook.getPageBudget() > 0 ? ebook.getPageBudget() : targetPages;
        int contentCeiling = Math.max(1, pageBudget - frontMatter);

        // If the user can't strictly afford the target (the ceiling minus the
        // overdraft buffer), plan a complete book for what they CAN afford rather
        // than planning the full target and running out of credits mid-book. The
        // overdraft stays as headroom for a natural ending.
        int affordable = Math.max(ContentBudget.MIN_TARGET_PAGES,
                pageBudget - Math.max(0, creditProperties.getMaxOverdraft()));
        int effectiveTarget = Math.min(targetPages, affordable);
        ContentBudget budget = ContentBudget.forTarget(effectiveTarget);

        // A fixed structure promised in the brief ("7-day plan", "10-step guide")
        // must be delivered in full; tell the planner so and check it afterwards.
        Optional<StructureRequirement> structure = StructureRequirement.detect(ebook);
        String structureHint = structure
                .map(s -> "This book promises a " + s.describe() + " structure. Every one of "
                        + "the " + s.count() + " " + s.unit() + "s must be covered in the plan; "
                        + "do not stop partway.")
                .orElse("");
        int structuralMinimum = structure.map(BookPlanningService::structuralMinimumPages).orElse(0);

        String system = PlanningPrompts.system(ebook.getLanguage());
        String user = PlanningPrompts.user(ebook, budget, Math.min(contentCeiling,
                Math.max(budget.softLimit(structuralMinimum), budget.contentTarget())), structureHint);

        String raw = anthropicService.complete(system, user, PLAN_MAX_TOKENS);
        BookPlan plan = parsePlan(raw);

        ebook.setTitle(orDefault(plan.title(), ebook.getTopic()));
        ebook.setSubtitle(plan.subtitle());
        ebook.setDescription(plan.description());
        ebook.setWritingGuidelines(plan.writingGuidelines());
        ebook.setPlanJson(raw);

        // Keep the outline near the selected target (the planner is asked to, but a
        // model left alone tends to expand), then enforce the hard credit ceiling
        // even if the model ignored it.
        List<PlannedChapter> planned = rebalanceToTarget(plan.chapters(),
                budget.softLimit(structuralMinimum),
                Math.max(budget.contentTarget(), structuralMinimum));
        planned = clampToBudget(planned, contentCeiling);

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
        int plannedPages = ebook.getChapters().stream().mapToInt(EbookChapter::getApproxPages).sum();
        log.info("Planned ebook {} — '{}' with {} chapters, {} content pages "
                        + "(selected target {}, planned against {}, soft limit {}, credit ceiling {})",
                ebookId, ebook.getTitle(), ebook.getChapters().size(), plannedPages,
                targetPages, effectiveTarget, budget.softLimit(structuralMinimum), contentCeiling);
        if (effectiveTarget < targetPages) {
            log.info("Ebook {}: credits cover ~{} pages, below the selected target {}; "
                    + "planned a complete book at the affordable size", ebookId, effectiveTarget, targetPages);
        }
        int chapterCount = ebook.getChapters().size();
        if (structure.isEmpty() && (chapterCount < budget.minChapters() || chapterCount > budget.maxChapters())) {
            log.info("Ebook {}: {} chapters is outside the usual {}–{} for a ~{}-page book (kept as planned)",
                    ebookId, chapterCount, budget.minChapters(), budget.maxChapters(), effectiveTarget);
        }

        // Structural completeness check: a promised fixed structure (N days/steps)
        // needs enough room to actually deliver every unit. This never fails the
        // book, but a shortfall is a strong signal the finished book may stop
        // partway, so it is logged loudly for observability.
        structure.ifPresent(req -> {
            if (ebook.getChapters().size() < req.count()) {
                log.warn("Ebook {} promises a {} structure but the plan has only {} chapters; "
                                + "the finished book may not cover every {}. Consider a larger page budget.",
                        ebookId, req.describe(), ebook.getChapters().size(), req.unit());
            }
        });
    }

    /**
     * Content pages a promised structure genuinely needs: every unit at a sensible
     * minimum depth (two pages — an explanation plus something to do), plus an
     * opening and a closing chapter. The soft limit never drops below this, so a
     * "7-day" book is never compressed below seven real days to hit a number.
     */
    static int structuralMinimumPages(StructureRequirement structure) {
        return structure.count() * 2 + 2;
    }

    /**
     * Keep the outline near the selected target. A plan whose total is within
     * {@code softLimit} is the planner's own design and is returned unchanged
     * (a book may legitimately land a little over its target). Above it, chapter
     * page counts are scaled down proportionally toward {@code landing} — chapters
     * are never dropped here, so every planned topic (and the conclusion) survives;
     * they are simply planned at a depth closer to what the user asked for. A plan
     * under the target is never inflated: no padding to reach a number.
     */
    static List<PlannedChapter> rebalanceToTarget(List<PlannedChapter> chapters, int softLimit, int landing) {
        int total = chapters.stream().mapToInt(pc -> Math.max(1, pc.approxPages())).sum();
        if (total <= Math.max(1, softLimit)) {
            return chapters;
        }
        double factor = (double) Math.max(1, landing) / total;
        List<PlannedChapter> scaled = new ArrayList<>(chapters.size());
        for (PlannedChapter pc : chapters) {
            int pages = Math.max(1, (int) Math.round(Math.max(1, pc.approxPages()) * factor));
            scaled.add(new PlannedChapter(pc.title(), pc.description(), pages));
        }
        log.info("Rebalanced plan from {} toward the target {} content pages (soft limit {})",
                total, landing, softLimit);
        return scaled;
    }

    /**
     * Force the planned chapters to fit within {@code budget} pages — the hard
     * credit ceiling. Each chapter keeps a floor of one page; if the model's totals
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
