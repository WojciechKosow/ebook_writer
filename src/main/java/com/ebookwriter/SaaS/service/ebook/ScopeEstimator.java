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
 * book even at Comprehensive, while 150 pages of material are never summarised
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
            // beyond a modest floor that leaves room to explain the essentials.
            book = Math.max(material + frontMatter, p.knowledgeBookFloor());
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

        int cap = Math.max(frontMatter + 1, maxPages);
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
                (int) Math.round(sourcePages), capped);
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
