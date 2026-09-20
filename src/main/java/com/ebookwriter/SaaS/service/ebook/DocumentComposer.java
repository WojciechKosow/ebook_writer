package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides the <b>page-type layout</b> of a book from its semantic structure — the
 * "book moments" that make the output feel designed rather than like a formatted
 * document. It looks at the chapters (are they a multi-day/step program? how many
 * are there? how long?) and the reserved page budget, and returns, per chapter,
 * how its opener should be composed:
 *
 * <ul>
 *   <li>{@link OpenerStyle#FULL_PAGE} — a dedicated opener page (label, big
 *       numeral, title, statement), with the body starting on the next page. The
 *       strongest divider; used for chapter/day openers when the book is
 *       substantial <em>and</em> the page budget can afford the extra pages.</li>
 *   <li>{@link OpenerStyle#BAND} — a strong opener band at the top of the content
 *       page (the default). No extra page, so it never pads a short or
 *       tightly-budgeted book — which is exactly requirement: hierarchy and
 *       pacing, not page count.</li>
 * </ul>
 *
 * <p>Program units (a chapter titled "Day 3", "Step 2", "Week 1", …) are detected
 * and get a distinct day/step treatment (label + optional duration + a "today's
 * change" statement). The decision is derived from the content structure, never
 * hardcoded per topic, and is a pure function (no I/O), so it is fully
 * unit-testable and identical in the preview and the PDF.
 */
public final class DocumentComposer {

    private DocumentComposer() {
    }

    /** How a chapter's opener is composed on the page. */
    public enum OpenerStyle {
        /** A strong opener band atop the content page (no extra page). */
        BAND,
        /** A dedicated opener page; the body begins on the following page. */
        FULL_PAGE
    }

    /**
     * The composed opener for one chapter.
     *
     * @param chapterNumber the chapter's 1-based number
     * @param style         BAND or FULL_PAGE
     * @param unit          true if this is a program unit (a "Day"/"Step"/… page)
     * @param label         the eyebrow, e.g. {@code "DAY 02"} or {@code "CHAPTER 03"}
     * @param numeral       the large display numeral, e.g. {@code "02"}
     * @param meta          optional duration chip (e.g. {@code "10–20 MINUTES"}), or null
     * @param statement     optional one-line opener statement, or null
     */
    public record ChapterLayout(int chapterNumber, OpenerStyle style, boolean unit,
                                String label, String numeral, String meta, String statement) {
    }

    /** Chapters shorter than this (in reserved pages) never justify a full opener page. */
    private static final int MIN_UNIT_CHAPTERS_FOR_PROGRAM = 3;

    /** A chapter title that names a program unit, e.g. "Day 3", "Step 2 — …", "Week 1". */
    private static final Pattern UNIT_TITLE = Pattern.compile(
            "^\\s*(?:the\\s+)?(day|week|month|step|part|stage|phase|module|lesson|session)"
                    + "\\s+(\\d{1,3}|[ivxlcdm]{1,7})\\b",
            Pattern.CASE_INSENSITIVE);

    /** A human-readable duration anywhere in a chapter, e.g. "10–20 minutes", "5 min". */
    private static final Pattern DURATION = Pattern.compile(
            "\\b(\\d{1,3})\\s*(?:[–—-]\\s*(\\d{1,3}))?\\s*(min|mins|minute|minutes|hour|hours|hr|hrs)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Compose the per-chapter layout for a book. Only chapters with content are
     * considered (blank ones aren't rendered). Never returns FULL_PAGE for a book
     * of one or two chapters, and never when the page budget has no room for the
     * extra opener pages — a short book stays compact.
     */
    public static List<ChapterLayout> compose(Ebook ebook, List<EbookChapter> chapters) {
        List<EbookChapter> rendered = new ArrayList<>();
        for (EbookChapter c : chapters) {
            if (c.getContent() != null && !c.getContent().isBlank()) {
                rendered.add(c);
            }
        }
        if (rendered.isEmpty()) {
            return List.of();
        }

        Optional<StructureRequirement> structure = StructureRequirement.detect(ebook);

        // Classify each chapter and remember its unit index (from the title).
        Map<Integer, Unit> units = new HashMap<>();
        int unitCount = 0;
        for (EbookChapter c : rendered) {
            Unit u = detectUnit(c.getTitle());
            if (u != null) {
                units.put(c.getChapterNumber(), u);
                unitCount++;
            }
        }
        // Treat titled units as a program only when several agree, or the brief
        // explicitly promised that structure — a lone "Part I" is not a program.
        boolean program = unitCount >= MIN_UNIT_CHAPTERS_FOR_PROGRAM
                || (structure.isPresent() && unitCount >= 2);

        int n = rendered.size();
        int contentPages = estimatedContentPages(rendered);
        int budget = ebook == null ? 0 : ebook.getPageBudget();

        // How many chapters would take a full opener page under each policy.
        int programOpeners = program ? unitCount : 0;
        boolean substantial = n >= 4 && contentPages >= n * 2; // avg >= 2 pages/chapter
        int chapterOpeners = (!program && substantial) ? n : 0;
        int wantedOpeners = Math.max(programOpeners, chapterOpeners);

        boolean fullPageAffordable = n > 2 && wantedOpeners > 0
                && canAfford(wantedOpeners, contentPages, budget);

        List<ChapterLayout> layouts = new ArrayList<>(n);
        int chapterDisplay = 0;
        for (EbookChapter c : rendered) {
            chapterDisplay++;
            Unit u = units.get(c.getChapterNumber());
            boolean isUnit = program && u != null;

            boolean fullPage;
            if (isUnit) {
                fullPage = fullPageAffordable;
            } else if (!program) {
                fullPage = fullPageAffordable; // uniform chapter openers
            } else {
                fullPage = false; // non-unit chapters in a program stay BAND (save pages)
            }

            String numeral = isUnit ? two(u.number) : two(chapterDisplay);
            String label = isUnit
                    ? u.word.toUpperCase(Locale.ROOT) + " " + numeral
                    : "CHAPTER " + numeral;
            String meta = isUnit ? duration(c) : null;
            String statement = openerStatement(c);

            layouts.add(new ChapterLayout(
                    c.getChapterNumber(),
                    fullPage ? OpenerStyle.FULL_PAGE : OpenerStyle.BAND,
                    isUnit, label, numeral, meta, statement));
        }
        return layouts;
    }

    // ---- Affordability ------------------------------------------------------

    /**
     * Whether the page budget has room for {@code openers} extra opener pages on
     * top of the front matter and content, so dedicated opener pages never eat
     * into the content the reader paid for (which the render-time trim would then
     * remove). An unknown budget ({@code <= 0}, e.g. a live preview) allows full
     * pages so the feature is visible; a real budget must show genuine slack.
     */
    static boolean canAfford(int openers, int contentPages, int budget) {
        if (budget <= 0) {
            return true;
        }
        int slack = budget - EbookHtmlBuilder.FRONT_MATTER_PAGES - contentPages;
        // Require room for at least ~60% of the wanted openers before committing to
        // dedicated pages; otherwise fall back to no-cost band openers.
        return slack >= (int) Math.ceil(openers * 0.6);
    }

    private static int estimatedContentPages(List<EbookChapter> chapters) {
        int planned = chapters.stream().mapToInt(c -> Math.max(0, c.getApproxPages())).sum();
        if (planned > 0) {
            return planned;
        }
        // No plan data (e.g. a hand-built preview): estimate from word count.
        int words = 0;
        for (EbookChapter c : chapters) {
            String content = c.getContent();
            if (content != null && !content.isBlank()) {
                words += content.strip().split("\\s+").length;
            }
        }
        return Math.max(chapters.size(), words / ChapterGenerationService.WORDS_PER_PAGE);
    }

    // ---- Detection helpers --------------------------------------------------

    record Unit(String word, int number) {
    }

    /** Detect a program-unit title ("Day 3", "Step 2 — …"); null if it isn't one. */
    static Unit detectUnit(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = UNIT_TITLE.matcher(title);
        if (!m.find()) {
            return null;
        }
        int number = parseNumber(m.group(2));
        if (number <= 0) {
            return null;
        }
        return new Unit(m.group(1).toLowerCase(Locale.ROOT), number);
    }

    private static int parseNumber(String token) {
        if (token == null || token.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            return romanToInt(token.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static int romanToInt(String s) {
        Map<Character, Integer> values = Map.of('i', 1, 'v', 5, 'x', 10, 'l', 50,
                'c', 100, 'd', 500, 'm', 1000);
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            Integer v = values.get(s.charAt(i));
            if (v == null) {
                return -1;
            }
            total += v < prev ? -v : v;
            prev = v;
        }
        return total > 0 && total < 400 ? total : -1;
    }

    /** The duration chip for a unit opener (e.g. {@code "10–20 MINUTES"}), or null. */
    static String duration(EbookChapter chapter) {
        String haystack = ((chapter.getDescription() == null ? "" : chapter.getDescription())
                + "\n" + (chapter.getContent() == null ? "" : chapter.getContent()));
        Matcher m = DURATION.matcher(haystack);
        if (!m.find()) {
            return null;
        }
        String lo = m.group(1);
        String hi = m.group(2);
        String unit = m.group(3).toLowerCase(Locale.ROOT);
        String word = unit.startsWith("h") ? (isPlural(lo, hi) ? "HOURS" : "HOUR")
                : (isPlural(lo, hi) ? "MINUTES" : "MINUTE");
        return (hi == null ? lo : lo + "–" + hi) + " " + word;
    }

    private static boolean isPlural(String lo, String hi) {
        String n = hi != null ? hi : lo;
        try {
            return Integer.parseInt(n) != 1;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * A one-line opener statement drawn from the chapter's planner description
     * (never fabricated): its first sentence, bounded so it stays a single line.
     */
    static String openerStatement(EbookChapter chapter) {
        String desc = chapter.getDescription();
        if (desc == null || desc.isBlank()) {
            return null;
        }
        String first = desc.strip().split("(?<=[.!?])\\s")[0].strip();
        if (first.length() > 160) {
            first = first.substring(0, 157).strip() + "…";
        }
        return first;
    }

    private static String two(int n) {
        return String.format("%02d", n);
    }
}
