package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step 1 for knowledge-based books — the replacement for
 * {@link BookPlanningService}. The author already approved the structure (the
 * BLUEPRINT_READY Book Blueprint), so there is no Claude planning call: the
 * blueprint's chapters become the book's {@link EbookChapter} rows (same model
 * the writer, editor, image pipeline and PDF renderer already use), each linked
 * to its blueprint chapter via {@code blueprintChapterId}.
 *
 * <p>Sizing reuses the legacy planner's rules: the selected target length
 * (capped by what the credits afford) is spread over the chapters by how much of
 * the author's knowledge each one carries, then held under the hard credit
 * ceiling with {@link BookPlanningService#clampToBudget} (which keeps the
 * book's final chapter if chapters must be dropped).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBookPlanner {

    private final EbookRepository ebookRepository;
    private final BookBlueprintService blueprintService;
    private final CreditProperties creditProperties;

    @Transactional
    public void plan(UUID ebookId) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        BookGenerationInput input = blueprintService.getGenerationInput(ebookId)
                .orElseThrow(() -> new IllegalStateException(
                        "The book blueprint is no longer ready — finish it before generating."));
        BlueprintData bp = input.blueprint();

        int targetPages = ebook.getApproxPageCount() > 0 ? ebook.getApproxPageCount()
                : Math.max(ContentBudget.MIN_TARGET_PAGES, creditProperties.getStandardTargetPages());
        int pageBudget = ebook.getPageBudget() > 0 ? ebook.getPageBudget() : targetPages;
        int contentCeiling = Math.max(1, pageBudget - EbookHtmlBuilder.FRONT_MATTER_PAGES);
        int affordable = Math.max(ContentBudget.MIN_TARGET_PAGES,
                pageBudget - Math.max(0, creditProperties.getMaxOverdraft()));
        ContentBudget budget = ContentBudget.forTarget(Math.min(targetPages, affordable));

        List<PlannedChapter> planned = distribute(bp.chapters(), budget.contentTarget());
        List<PlannedChapter> kept = BookPlanningService.clampToBudget(planned, contentCeiling);

        ebook.setTitle(orDefault(bp.workingTitle(), ebook.getTopic()));
        ebook.setSubtitle(blankToNull(bp.subtitle()));
        ebook.setDescription(orDefault(bp.concept(), bp.promise()));
        ebook.setWritingGuidelines(writingGuidelines(ebook, bp));
        ebook.setPlanJson(null);

        ebook.getChapters().clear();
        int number = 1;
        int cursor = 0; // clamping keeps order, so walk the blueprint alongside
        for (PlannedChapter pc : kept) {
            BlueprintData.Chapter source = null;
            while (cursor < bp.chapters().size() && source == null) {
                BlueprintData.Chapter candidate = bp.chapters().get(cursor++);
                if (candidate.title().equals(pc.title())) source = candidate;
            }
            ebook.getChapters().add(EbookChapter.builder()
                    .ebook(ebook)
                    .chapterNumber(number++)
                    .title(pc.title())
                    .description(pc.description())
                    .approxPages(Math.max(1, pc.approxPages()))
                    .blueprintChapterId(source == null ? null : source.id())
                    .status(ChapterStatus.PENDING)
                    .build());
        }
        ebookRepository.save(ebook);
        if (kept.size() < planned.size()) {
            log.warn("Ebook {}: the credit ceiling ({} content pages) holds {} of the blueprint's {} chapters; "
                    + "the final chapter was kept", ebookId, contentCeiling, kept.size(), planned.size());
        }
        log.info("Planned knowledge-based ebook {} from its blueprint: '{}', {} chapters, {} content pages "
                        + "(target {}, credit ceiling {})", ebookId, ebook.getTitle(), ebook.getChapters().size(),
                kept.stream().mapToInt(PlannedChapter::approxPages).sum(), budget.contentTarget(), contentCeiling);
    }

    /**
     * Spread {@code contentPages} over the blueprint chapters, weighted by how much
     * of the author's material each chapter carries (topics, key points, knowledge
     * references), so a chapter mapped to a lot of real knowledge gets room for it
     * and a short orientation chapter stays short. Every chapter gets at least one
     * page. Pure; unit-tested.
     */
    static List<PlannedChapter> distribute(List<BlueprintData.Chapter> chapters, int contentPages) {
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (BlueprintData.Chapter c : chapters) {
            int w = Math.min(12, 2 + c.topics().size() + c.keyPoints().size() + c.knowledgeReferences().size());
            weights.add(w);
            total += w;
        }
        List<PlannedChapter> out = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            BlueprintData.Chapter c = chapters.get(i);
            int pages = Math.max(1, (int) Math.round((double) contentPages * weights.get(i) / Math.max(1, total)));
            out.add(new PlannedChapter(c.title(), describe(c), pages));
        }
        return out;
    }

    /** The chapter's scope line for the outline: purpose plus topics. */
    static String describe(BlueprintData.Chapter c) {
        StringBuilder sb = new StringBuilder(c.purpose() == null ? "" : c.purpose().strip());
        if (!c.topics().isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Covers: ").append(String.join(", ", c.topics())).append(".");
        }
        return sb.toString();
    }

    /** Book-wide guidelines derived from the blueprint (shown to the writer and the editor). */
    static String writingGuidelines(Ebook e, BlueprintData bp) {
        StringBuilder sb = new StringBuilder();
        sb.append("This book is written from the author's own knowledge and project, not from a generic outline.\n");
        if (bp.audience() != null) sb.append("Reader: ").append(bp.audience()).append(".\n");
        if (bp.readerGoal() != null) sb.append("Reader's goal: ").append(bp.readerGoal()).append(".\n");
        if (bp.promise() != null) sb.append("Promise: ").append(bp.promise()).append("\n");
        if (bp.structureRationale() != null) sb.append("Structure: ").append(bp.structureRationale()).append("\n");
        if (e.getStyle() != null && !e.getStyle().isBlank()) sb.append("Style: ").append(e.getStyle().strip()).append(".\n");
        sb.append("Use the author's project names, decisions, steps and experiences; never replace them with "
                + "generic examples or invented ones.");
        return sb.toString();
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.strip();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
