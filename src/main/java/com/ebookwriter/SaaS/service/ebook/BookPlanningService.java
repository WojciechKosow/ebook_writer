package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.plan.BookPlan;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.PlanningPrompts;
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

    @Transactional
    public void plan(UUID ebookId) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));

        // The reserved page budget is a hard ceiling: the model's plan may not
        // sum to more pages than this, or we'd generate (and pay for) more than
        // the user authorised. Fall back to the requested count for legacy rows.
        int pageBudget = ebook.getPageBudget() > 0 ? ebook.getPageBudget()
                : Math.max(1, ebook.getApproxPageCount());

        // Aim for the length the user actually asked for; the reserved budget is
        // only a ceiling for the headroom. The cover and table of contents cost
        // pages too, so both are expressed in content pages (minus front matter).
        int requestedPages = Math.max(1, ebook.getApproxPageCount());
        int frontMatter = EbookHtmlBuilder.FRONT_MATTER_PAGES;
        int contentTarget = Math.max(1, requestedPages - frontMatter);
        int contentCeiling = Math.max(contentTarget, pageBudget - frontMatter);

        // A fixed structure promised in the brief ("7-day plan", "10-step guide")
        // must be delivered in full; tell the planner so and check it afterwards.
        Optional<StructureRequirement> structure = StructureRequirement.detect(ebook);
        String structureHint = structure
                .map(s -> "This book promises a " + s.describe() + " structure. Every one of "
                        + "the " + s.count() + " " + s.unit() + "s must be covered in the plan; "
                        + "do not stop partway.")
                .orElse("");

        String system = PlanningPrompts.system(ebook.getLanguage());
        String user = PlanningPrompts.user(ebook, contentTarget, contentCeiling, structureHint);

        String raw = anthropicService.complete(system, user, PLAN_MAX_TOKENS);
        BookPlan plan = parsePlan(raw);

        ebook.setTitle(orDefault(plan.title(), ebook.getTopic()));
        ebook.setSubtitle(plan.subtitle());
        ebook.setDescription(plan.description());
        ebook.setWritingGuidelines(plan.writingGuidelines());
        ebook.setPlanJson(raw);

        // Enforce the ceiling even if the model ignored it in the prompt.
        List<PlannedChapter> planned = clampToBudget(plan.chapters(), contentCeiling);

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
        log.info("Planned ebook {} — '{}' with {} chapters, {} content pages (target {}, ceiling {}, budget {})",
                ebookId, ebook.getTitle(), ebook.getChapters().size(),
                ebook.getChapters().stream().mapToInt(EbookChapter::getApproxPages).sum(),
                contentTarget, contentCeiling, pageBudget);

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
     * Force the planned chapters to fit within {@code budget} pages. Each chapter
     * keeps a floor of one page; if the model's totals exceed the budget the
     * per-chapter page counts are scaled down proportionally, and if it planned
     * more chapters than the budget can hold at one page each the tail is
     * dropped. Returns page counts that sum to at most {@code budget}.
     */
    static List<PlannedChapter> clampToBudget(List<PlannedChapter> chapters, int budget) {
        // If even one page per chapter overflows the budget, keep only as many
        // chapters as the budget can afford.
        List<PlannedChapter> kept = chapters.size() > budget
                ? new ArrayList<>(chapters.subList(0, budget))
                : new ArrayList<>(chapters);

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
