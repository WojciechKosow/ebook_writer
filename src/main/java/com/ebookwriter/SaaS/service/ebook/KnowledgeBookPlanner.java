package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.BookDepth;
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
 * <p>Sizing follows the content, not a page count: each chapter gets the room
 * its share of the author's knowledge needs at the selected depth (see
 * {@link #size}), and the sum is the planned length. If the user's credits don't
 * cover that, planning stops with {@link PlanExceedsCreditsException} instead of
 * cutting chapters; only the per-book safety maximum can shrink a plan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBookPlanner {

    private final EbookRepository ebookRepository;
    private final BookBlueprintService blueprintService;
    private final ScopeEstimationService scopeEstimationService;

    @Transactional
    public void plan(UUID ebookId) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        BookGenerationInput input = blueprintService.getGenerationInput(ebookId)
                .orElseThrow(() -> new IllegalStateException(
                        "The book blueprint is no longer ready — finish it before generating."));
        BlueprintData bp = input.blueprint();

        // Size each chapter by how much of the author's knowledge it carries at the
        // selected depth; the sum is the book's planned length. No user page count
        // is involved — only the per-book safety maximum, and the credit check.
        BookDepth depth = ebook.effectiveDepth();
        ScopeEstimate estimate = scopeEstimationService.estimate(ebook);
        List<PlannedChapter> planned = size(bp.chapters(), depth,
                estimate.midpoint() - EbookHtmlBuilder.FRONT_MATTER_PAGES);
        List<PlannedChapter> kept = BookPlanningService.clampToBudget(planned,
                scopeEstimationService.maxPages() - EbookHtmlBuilder.FRONT_MATTER_PAGES);
        int plannedBook = BookPlanningService.totalPages(kept) + EbookHtmlBuilder.FRONT_MATTER_PAGES;
        BookPlanningService.requireAffordable(ebookId, plannedBook, ebook.getPageBudget());

        ebook.setTitle(orDefault(bp.workingTitle(), ebook.getTopic()));
        ebook.setSubtitle(blankToNull(bp.subtitle()));
        ebook.setDescription(orDefault(bp.concept(), bp.promise()));
        ebook.setWritingGuidelines(writingGuidelines(ebook, bp));
        ebook.setPlanJson(null);
        ebook.setPlannedPages(plannedBook);

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
            log.warn("Ebook {}: the per-book safety maximum holds {} of the blueprint's {} chapters; "
                    + "the final chapter was kept", ebookId, kept.size(), planned.size());
        }
        log.info("Planned knowledge-based ebook {} from its blueprint at depth {}: '{}', {} chapters, ~{} pages "
                        + "(estimate {}–{}, credit ceiling {})", ebookId, depth, ebook.getTitle(),
                ebook.getChapters().size(), plannedBook, estimate.pagesLow(), estimate.pagesHigh(),
                ebook.getPageBudget());
    }

    /**
     * How far the blended estimate (which also weighs the raw volume and breadth
     * of the materials) may move the per-chapter sizes. Keeps each chapter's size
     * anchored in what that chapter itself carries.
     */
    static final double MIN_SCALE = 0.6;
    static final double MAX_SCALE = 1.6;

    /**
     * Size the blueprint chapters at this depth. Each chapter gets the room its
     * own knowledge needs ({@link ScopeEstimator#chapterPages}), so a chapter
     * mapped to a lot of the author's material gets space for it and a short
     * orientation chapter stays short. The sizes are then scaled (within
     * {@link #MIN_SCALE}–{@link #MAX_SCALE}) toward {@code estimatedContentPages}, the
     * estimate that also accounts for the materials' overall volume. Every chapter
     * gets at least one page. Pure; unit-tested.
     */
    static List<PlannedChapter> size(List<BlueprintData.Chapter> chapters, BookDepth depth,
                                     int estimatedContentPages) {
        List<Double> raw = new ArrayList<>();
        double total = 0;
        for (BlueprintData.Chapter c : chapters) {
            double pages = ScopeEstimator.chapterPagesExact(depth, ScopeEstimationService.chapterSignal(c));
            raw.add(pages);
            total += pages;
        }
        double scale = total <= 0 ? 1.0
                : Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.max(1, estimatedContentPages) / total));
        List<PlannedChapter> out = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            BlueprintData.Chapter c = chapters.get(i);
            int pages = Math.max(1, (int) Math.round(raw.get(i) * scale));
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
