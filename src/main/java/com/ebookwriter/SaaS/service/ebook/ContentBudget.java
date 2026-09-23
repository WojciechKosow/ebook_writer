package com.ebookwriter.SaaS.service.ebook;

/**
 * The <b>soft content budget</b> a book is planned against — derived from the
 * length the user selected (~20 / 30 / 50 / 75 / 100 pages) before any long-form
 * writing begins.
 *
 * <p>Two separate concepts drive generation and must never be confused:
 * <ul>
 *   <li><b>Target length</b> (this class) — how much content we <em>ideally</em>
 *       want. It shapes the outline: chapter count, chapter depth, exercises,
 *       supporting material and visual density. It is a planning signal, never a
 *       hard page limit: a book may naturally land a little under it or run past
 *       it when the material genuinely needs the room.</li>
 *   <li><b>Credit ceiling</b> ({@code Ebook.pageBudget}) — how much the user is
 *       actually <em>allowed</em> to generate. Credits are permission to continue,
 *       not an instruction to write more; the book stops when the content is
 *       complete, not when the credits run out.</li>
 * </ul>
 *
 * <p>All figures are in <em>content</em> pages (front matter excluded). The class
 * is a pure value object so the budgeting rules are unit-testable.
 *
 * @param targetPages         the whole-book target the user selected
 * @param contentTarget       the content pages that target implies (minus front matter)
 * @param minChapters         the fewest chapters a complete book at this scale should have
 * @param maxChapters         the most chapters this scale comfortably supports
 * @param exercisesPerChapter a typical number of practical components per chapter
 * @param maxInlineVisuals    a sensible upper bound on illustrations for the book
 * @param depth               a short description of the expected depth, for prompts
 */
public record ContentBudget(int targetPages,
                            int contentTarget,
                            int minChapters,
                            int maxChapters,
                            int exercisesPerChapter,
                            int maxInlineVisuals,
                            String depth) {

    /** Smallest target the product offers; anything lower is lifted to it. */
    public static final int MIN_TARGET_PAGES = 10;

    /**
     * How far a plan may exceed the content target before it is rebalanced toward
     * it. A plan within this band is left exactly as the planner designed it; a
     * genuine structural need (every promised day of a 7-day program) may still go
     * beyond, see {@link #softLimit(int)}.
     */
    static final double OVER_TARGET_TOLERANCE = 0.25;

    /**
     * Build the budget for a selected whole-book target. The bands are calibrated
     * on the 6×9" layout (~200 words per content page, see
     * {@code ChapterGenerationService.WORDS_PER_PAGE}).
     */
    public static ContentBudget forTarget(int targetPages) {
        int target = Math.max(MIN_TARGET_PAGES, targetPages);
        int content = Math.max(1, target - EbookHtmlBuilder.FRONT_MATTER_PAGES);

        if (target <= 24) {
            return new ContentBudget(target, content, 4, 6, 1, 2,
                    "focused and practical: one clear idea per chapter, tight explanations, "
                            + "a single worked example where it matters");
        }
        if (target <= 40) {
            return new ContentBudget(target, content, 5, 8, 1, 4,
                    "practical with room for depth: each chapter explains, shows an example "
                            + "and gives the reader something to do");
        }
        if (target <= 60) {
            return new ContentBudget(target, content, 7, 11, 2, 5,
                    "substantial: fuller explanations, several supporting examples, "
                            + "workbook material and frameworks per chapter");
        }
        if (target <= 85) {
            return new ContentBudget(target, content, 9, 14, 2, 6,
                    "comprehensive: thorough treatment of each topic, case studies, "
                            + "multiple exercises and reference material");
        }
        return new ContentBudget(target, content, 11, 18, 3, 6,
                "definitive: complete coverage of the subject, in-depth chapters, "
                        + "extensive practice material, frameworks and reference sections");
    }

    /**
     * The plan size above which the outline is rebalanced toward the target
     * (content pages). A plan at or below this is the planner's own design and is
     * left untouched; above it, the book would drift far past what the user asked
     * for, which is the "keeps expanding until credits run out" failure mode.
     *
     * @param structuralMinimum content pages a promised structure genuinely needs
     *                          (e.g. seven days at a sensible minimum depth); the
     *                          limit never drops below it, so a real structure is
     *                          never compressed to hit a number.
     */
    public int softLimit(int structuralMinimum) {
        int tolerant = (int) Math.ceil(contentTarget * (1.0 + OVER_TARGET_TOLERANCE));
        return Math.max(tolerant, structuralMinimum);
    }

    /** The low end of the natural landing range, for prompts ("aim for 26–34"). */
    public int rangeLow() {
        return Math.max(1, (int) Math.round(targetPages * 0.9));
    }

    /** The high end of the natural landing range, for prompts. */
    public int rangeHigh() {
        return (int) Math.round(targetPages * 1.15);
    }
}
