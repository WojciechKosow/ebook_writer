package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.BookDepth;

import java.util.List;

/**
 * Estimates how long a book needs to be from <b>both</b> the requested depth and
 * the material actually available — never from a page count the user typed.
 *
 * <p>It is deliberately not {@code pages = sourcePages × k}. Several signals are
 * weighed, from the weakest to the strongest:
 * <ol>
 *   <li><b>The brief.</b> Without materials, the depth sets a typical scope for a
 *       book on one topic, widened a little by a rich brief or a promised
 *       structure (a "10-step guide" needs ten real steps).</li>
 *   <li><b>Source volume</b>, with diminishing returns: the first pages of
 *       material add almost one-for-one, while very large uploads (whole
 *       repositories, long PDFs) taper off, because a book selects and explains —
 *       it does not reprint its sources. Depth decides how much of that volume
 *       survives: Quick is selective, Comprehensive uses the material broadly.</li>
 *   <li><b>Distinct knowledge</b> — topics, processes, examples, technical details
 *       and insights Scrivetta extracted from the materials. Each needs some room
 *       to be explained, more at a deeper level.</li>
 *   <li><b>The approved chapter structure</b> (blueprint), the strongest signal:
 *       each chapter is sized by how much of the author's knowledge it carries.</li>
 * </ol>
 *
 * <p>For a knowledge-based book the materials define the scope (the writer never
 * invents what the author did not provide), so a few pages of notes give a modest
 * book even at Comprehensive — bounded by how far the material can be expanded
 * ({@link #materialCeilingPages}) — while 150 pages of material are never summarised
 * into 30 pages. For a brief-only book the depth sets the scope and pasted source
 * text adds to it.
 *
 * <p>Pure and static, so every rule is unit-tested.
 */
public final class ScopeEstimator {

    private ScopeEstimator() {
    }

    /** Characters in one page of typical source material (notes, docs, prose, code). */
    static final double SOURCE_CHARS_PER_PAGE = 2600;

    /** Source volume (pages) around which additional material starts to taper off. */
    static final double VOLUME_TAPER_PAGES = 120;

    /** One blueprint chapter: how much of the author's knowledge and material it carries. */
    public record ChapterSignal(int topics, int keyPoints, int knowledgeRefs, int sourceRefs) {

        /**
         * Knowledge weight. Knowledge and source references overlap with the key
         * points they back, so they count half.
         */
        double weight() {
            return Math.min(16, topics + keyPoints + knowledgeRefs * 0.5 + sourceRefs * 0.5);
        }
    }

    /**
     * Everything the estimate is based on.
     *
     * @param depth             the selected depth
     * @param knowledgeFlow     true when the book is written from uploaded materials
     * @param briefChars        length of the brief's free text (topic, goal, instructions)
     * @param structureUnits    units of a structure the brief promises (7 days, 10 steps), 0 for none
     * @param sourceChars       characters of source material (pasted text or analysed materials)
     * @param knowledgeUnits    distinct knowledge items extracted from the materials
     * @param blueprintChapters the approved chapter structure, empty if there is none yet
     */
    public record Signals(BookDepth depth,
                          boolean knowledgeFlow,
                          int briefChars,
                          int structureUnits,
                          long sourceChars,
                          int knowledgeUnits,
                          List<ChapterSignal> blueprintChapters) {

        public Signals {
            depth = BookDepth.orDefault(depth);
            blueprintChapters = blueprintChapters == null ? List.of() : List.copyOf(blueprintChapters);
        }

        /** A brief-only book (no materials yet). */
        public static Signals brief(BookDepth depth, int briefChars, int structureUnits, long sourceChars) {
            return new Signals(depth, false, briefChars, structureUnits, sourceChars, 0, List.of());
        }
    }

    /** Per-depth calibration. All page figures are content pages unless noted. */
    record Profile(double briefBookPages,      // typical whole book from a brief alone
                   double volumeFactor,        // how much of the (tapered) source volume survives
                   double pagesPerKnowledgeUnit,
                   double chapterBasePages,    // every chapter needs an opening, framing and close
                   double pagesPerChapterWeight,
                   double chapterCapPages,     // no single chapter balloons
                   double knowledgeBookFloor,  // smallest sensible whole book from materials
                   double pagesPerStructureUnit,
                   double avgChapterPages) {   // for the chapter-count estimate
    }

    static Profile profile(BookDepth depth) {
        return switch (BookDepth.orDefault(depth)) {
            case QUICK -> new Profile(18, 0.5, 0.55, 1.5, 0.25, 8, 12, 1.5, 3.5);
            case STANDARD -> new Profile(34, 1.05, 1.2, 2, 0.5, 16, 20, 2.5, 5);
            case COMPREHENSIVE -> new Profile(60, 1.75, 2.0, 3, 0.85, 28, 30, 4, 7);
        };
    }

    /**
     * Estimate the whole-book length range for these signals.
     *
     * @param maxPages the per-book safety maximum; the range never exceeds it
     */
    public static ScopeEstimate estimate(Signals s, int maxPages) {
        Profile p = profile(s.depth());
        int frontMatter = EbookHtmlBuilder.FRONT_MATTER_PAGES;
        double sourcePages = Math.max(0, s.sourceChars()) / SOURCE_CHARS_PER_PAGE;

        // Material-driven content pages (0 when there is no material).
        double volume = taperedVolume(sourcePages) * p.volumeFactor();
        double knowledge = Math.max(0, s.knowledgeUnits()) * p.pagesPerKnowledgeUnit();
        double material = (volume > 0 && knowledge > 0) ? 0.5 * volume + 0.5 * knowledge : Math.max(volume, knowledge);
        if (!s.blueprintChapters().isEmpty()) {
            double chapters = s.blueprintChapters().stream().mapToDouble(c -> chapterPagesRaw(p, c)).sum();
            material = material > 0 ? 0.65 * chapters + 0.35 * material : chapters;
        }

        double book; // whole-book pages
        if (s.knowledgeFlow()) {
            // The materials define the scope: no padding past what they support,
            // beyond a modest floor that leaves room to explain the essentials —
            // and never more book than the material can carry (see materialCeilingPages).
            book = Math.max(material + frontMatter, p.knowledgeBookFloor());
            if (sourcePages > 0) {
                book = Math.min(book, Math.max(Math.max(p.knowledgeBookFloor(),
                                structuralMinimumPages(s.blueprintChapters().size())),
                        materialCeilingPages(s.depth(), sourcePages) + frontMatter));
            }
        } else {
            double brief = p.briefBookPages() * briefRichness(s.briefChars());
            // Pasted source text adds to what the topic itself calls for.
            book = material > 0 ? Math.max(brief, material + 0.3 * brief + frontMatter) : brief;
        }
        if (s.structureUnits() > 0) {
            book = Math.max(book, s.structureUnits() * p.pagesPerStructureUnit() + 2 + frontMatter);
        }

        ScopeEstimate.Basis basis = !s.blueprintChapters().isEmpty() ? ScopeEstimate.Basis.BLUEPRINT
                : s.knowledgeFlow() ? ScopeEstimate.Basis.KNOWLEDGE
                : sourcePages >= 1 ? ScopeEstimate.Basis.SOURCE_TEXT
                : ScopeEstimate.Basis.BRIEF;
        // The less Scrivetta knows, the wider the honest range.
        double spread = switch (basis) {
            case BRIEF -> 0.2;
            case SOURCE_TEXT, KNOWLEDGE -> 0.15;
            case BLUEPRINT -> 0.12;
        };

        int cap = Math.max(frontMatter + 1, Math.min(maxPages, depthCap(s.depth())));
        boolean capped = book * (1 + spread) > cap;
        int high = Math.min(cap, nice(book * (1 + spread)));
        int low = Math.min(high, Math.max(frontMatter + 1, nice(book * (1 - spread))));

        int chaptersLow;
        int chaptersHigh;
        if (!s.blueprintChapters().isEmpty()) {
            chaptersLow = chaptersHigh = s.blueprintChapters().size();
        } else {
            double chapters = Math.max(3, (book - frontMatter) / p.avgChapterPages());
            if (s.structureUnits() > 0) {
                chapters = Math.max(chapters, s.structureUnits() + 1);
            }
            chaptersLow = Math.max(3, (int) Math.round(chapters * 0.8));
            chaptersHigh = Math.max(chaptersLow + 1, (int) Math.round(chapters * 1.2));
        }
        return new ScopeEstimate(s.depth(), low, high, chaptersLow, chaptersHigh, basis,
                (int) Math.round(sourcePages), capped, false);
    }

    /**
     * The most pages a book at this depth may ever be planned or estimated at,
     * however much material there is. A Quick book stays a quick read; only a
     * Comprehensive book may reach the per-book safety maximum.
     */
    public static int depthCap(BookDepth depth) {
        return switch (BookDepth.orDefault(depth)) {
            case QUICK -> 90;
            case STANDARD -> 250;
            case COMPREHENSIVE -> 400;
        };
    }

    /** Words in one page of source material ({@link #SOURCE_CHARS_PER_PAGE} characters). */
    static final double SOURCE_WORDS_PER_PAGE = 430;

    /** Book pages one page of source material fills, at 200 words per book page. */
    static final double BOOK_PAGES_PER_SOURCE_PAGE = SOURCE_WORDS_PER_PAGE / 200.0;

    /**
     * The most a book written from the author's materials may expand them, in
     * words, at each depth. A book explains, gives examples and exercises, so it
     * is longer than terse notes — but only so much longer before it stops being
     * the author's book and becomes generic filler around it (eight pages of a
     * photographer's notes do not make a 200-page book on light).
     */
    static double maxExpansion(BookDepth depth) {
        return switch (BookDepth.orDefault(depth)) {
            case QUICK -> 1.25;
            case STANDARD -> 2.5;
            case COMPREHENSIVE -> 4.0;
        };
    }

    /**
     * Content pages the author's materials can carry at this depth: their length
     * in words times {@link #maxExpansion}. Large uploads (repositories, long
     * PDFs) are far above every other bound, so this only ever limits books
     * written from a small amount of material.
     */
    public static double materialCeilingPages(BookDepth depth, double sourcePages) {
        return Math.max(0, sourcePages) * BOOK_PAGES_PER_SOURCE_PAGE * maxExpansion(depth);
    }

    /**
     * The smallest book a structure of {@code chapters} chapters physically makes:
     * every chapter at the writer's minimum length, with its opener — the same
     * measure the credit hold uses ({@link WritingBudget#holdFor}). The material
     * ceiling never pushes an estimate below it, so an approved blueprint can always
     * be written within the credits its estimate asked for. 0 without a structure.
     */
    public static int structuralMinimumPages(int chapters) {
        if (chapters <= 0) {
            return 0;
        }
        return WritingBudget.holdFor((long) chapters * WritingBudget.MIN_CHAPTER_WORDS, chapters, 0);
    }

    /** How far OpenAI's judgement may move the estimate away from the material-based one. */
    static final double AI_MIN_FACTOR = 0.6;
    static final double AI_MAX_FACTOR = 1.8;

    /**
     * Combine the material-based estimate with OpenAI's scope assessment. The AI
     * judges the content (how much there really is to explain); the material-based
     * estimate keeps it honest: the AI's midpoint may move the estimate only within
     * {@link #AI_MIN_FACTOR}–{@link #AI_MAX_FACTOR}× of it, never past the depth's cap
     * ({@link #depthCap}) or the per-book maximum. So a generous guess can never turn
     * a few pages of notes into a 500-page book. An invalid AI range is ignored.
     */
    public static ScopeEstimate combine(ScopeEstimate heuristic, AiScopeAssessment.Range ai, int maxPages) {
        if (ai == null || ai.pagesLow() <= 0 || ai.pagesHigh() < ai.pagesLow()) {
            return heuristic;
        }
        int frontMatter = EbookHtmlBuilder.FRONT_MATTER_PAGES;
        int cap = Math.max(frontMatter + 1, Math.min(maxPages, depthCap(heuristic.depth())));
        double hMid = (heuristic.pagesLow() + heuristic.pagesHigh()) / 2.0;
        double aMid = (ai.pagesLow() + ai.pagesHigh()) / 2.0;
        double mid = Math.max(hMid * AI_MIN_FACTOR, Math.min(hMid * AI_MAX_FACTOR, aMid));
        if (heuristic.basis() == ScopeEstimate.Basis.KNOWLEDGE || heuristic.basis() == ScopeEstimate.Basis.BLUEPRINT) {
            // A book written from the author's materials: the AI may not judge it
            // longer than the material can carry, however generous its guess.
            int chapters = heuristic.basis() == ScopeEstimate.Basis.BLUEPRINT ? heuristic.chaptersHigh() : 0;
            double ceiling = heuristic.sourcePages() > 0
                    ? Math.max(Math.max(profile(heuristic.depth()).knowledgeBookFloor(), structuralMinimumPages(chapters)),
                            materialCeilingPages(heuristic.depth(), heuristic.sourcePages()) + frontMatter)
                    : Double.MAX_VALUE;
            mid = Math.min(mid, Math.max(hMid, ceiling));
        }
        double spread = Math.max(0.08, Math.min(0.25, (ai.pagesHigh() - ai.pagesLow()) / (2.0 * aMid)));
        boolean capped = heuristic.capped() || mid * (1 + spread) > cap;
        int high = Math.min(cap, nice(mid * (1 + spread)));
        int low = Math.min(high, Math.max(frontMatter + 1, nice(mid * (1 - spread))));
        return new ScopeEstimate(heuristic.depth(), low, high, heuristic.chaptersLow(), heuristic.chaptersHigh(),
                heuristic.basis(), heuristic.sourcePages(), capped, true);
    }

    /**
     * Content pages one blueprint chapter needs at this depth, from how much of
     * the author's knowledge it carries. Used by the estimate and by
     * {@link KnowledgeBookPlanner} so the plan and the estimate agree.
     */
    public static int chapterPages(BookDepth depth, ChapterSignal chapter) {
        return Math.max(1, (int) Math.round(chapterPagesExact(depth, chapter)));
    }

    /** {@link #chapterPages} before rounding, for proportional sizing. */
    public static double chapterPagesExact(BookDepth depth, ChapterSignal chapter) {
        return chapterPagesRaw(profile(depth), chapter);
    }

    private static double chapterPagesRaw(Profile p, ChapterSignal c) {
        return Math.min(p.chapterCapPages(), p.chapterBasePages() + p.pagesPerChapterWeight() * c.weight());
    }

    /**
     * Source volume with diminishing returns: ~1:1 for the first pages, tapering
     * for very large uploads ({@code T·ln(1 + pages/T)}).
     */
    static double taperedVolume(double sourcePages) {
        if (sourcePages <= 0) {
            return 0;
        }
        return VOLUME_TAPER_PAGES * Math.log1p(sourcePages / VOLUME_TAPER_PAGES);
    }

    /** A richer brief (detailed goal and instructions) widens the scope a little — at most +25%. */
    static double briefRichness(int briefChars) {
        return 1 + Math.min(0.25, Math.max(0, briefChars - 200) / 6000.0);
    }

    /** Round to a friendly figure: whole pages up to 40, then the nearest 5. */
    static int nice(double pages) {
        if (pages <= 40) {
            return (int) Math.round(pages);
        }
        return (int) (Math.round(pages / 5.0) * 5);
    }
}
