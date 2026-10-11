package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.service.ebook.QualityReport.Category;
import com.ebookwriter.SaaS.service.ebook.QualityReport.Finding;
import com.ebookwriter.SaaS.service.ebook.QualityReport.Severity;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The quality pass over a book once it is typeset: a report for the author,
 * before publishing, of everything a careful editor would flag — each with its
 * chapter and the page it landed on. Every check is structural and works the
 * same for any genre, language or subject:
 * <ol>
 *   <li><b>incomplete units</b> — a chapter that stops mid-sentence, on a
 *       heading, inside an open block, or after announcing a block that never
 *       comes; a chapter missing from the book;</li>
 *   <li><b>broken structure</b> — empty headings, list numbers or bullets
 *       standing alone, the same block printed twice, recurring sections that
 *       are not the same in every chapter;</li>
 *   <li><b>internal data reaching the reader</b> — printed text that matches a
 *       brief, a summary or a note written for the model; reader text cut off
 *       with an ellipsis;</li>
 *   <li><b>verbatim lines too wide for the page</b> — set smaller, or carried
 *       over with a continuation mark;</li>
 *   <li><b>repetition</b> — topics explained in several chapters, and the
 *       phrases most often repeated across chapters;</li>
 *   <li><b>length</b> — every chapter and the book against its planned budget.</li>
 * </ol>
 * Pure: it reads the chapters, the renderer's notes, the final HTML and the
 * laid-out geometry, and writes nothing.
 */
public final class BookQualityInspector {

    private BookQualityInspector() {
    }

    /** A chapter this far off its planned pages (and at least {@link #MIN_PAGE_GAP} pages) is flagged. */
    static final double CHAPTER_TOLERANCE = 0.35;
    static final int MIN_PAGE_GAP = 2;
    /** The book this far off its planned pages is flagged. */
    static final double BOOK_TOLERANCE = ChapterPrompts.LENGTH_TOLERANCE;
    /** Words per phrase in the repetition statistics. */
    static final int PHRASE_WORDS = 5;
    static final int TOP_PHRASES = 10;
    /** Share of a printed paratext's word sequences found in an internal field for it to count as leaked. */
    static final double LEAK_SHARE = 0.6;

    private static final Pattern ORPHAN_MARKER = Pattern.compile("^\\s*(?:\\d{1,3}[.)]?|[-*•–])\\s*$");

    /**
     * Inspect a typeset book.
     *
     * @param chapters every chapter, with its internal fields (for leak detection)
     * @param ctx      the render context of the final build (the renderer's notes)
     * @param doc      the final book HTML, as laid out (with layout ids)
     * @param layout   the laid-out geometry, or null when unavailable (no pages then)
     * @param pages    pages in the rendered PDF
     */
    public static QualityReport inspect(Ebook ebook, List<EbookChapter> chapters, RenderContext ctx, Document doc,
                                        LayoutSnapshot layout, int pages) {
        Locator at = new Locator(doc, layout);
        List<EbookChapter> inBook = ManuscriptContext.inBook(chapters);
        List<EbookChapter> printed = inBook.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank()).toList();
        Map<Integer, String> titles = new HashMap<>();
        for (EbookChapter c : chapters) {
            titles.put(c.getChapterNumber(), c.getTitle());
        }
        List<Finding> findings = new ArrayList<>();
        Adder add = (category, severity, chapter, page, message, excerpt) -> findings.add(new Finding(
                category, severity, chapter, chapter == null ? null : titles.get(chapter), page, message,
                excerpt == null || excerpt.isBlank() ? null : excerpt));

        incompleteUnits(inBook, printed, at, add);
        rendererNotes(ctx, at, add);
        structure(printed, at, add);
        template(ebook, printed, at, add);
        leaks(ebook, chapters, at, add);
        repeatedTopics(printed, at, add);
        List<QualityReport.RepeatedPhrase> phrases = repeatedPhrases(printed, at);
        for (QualityReport.RepeatedPhrase p : phrases.subList(0, Math.min(5, phrases.size()))) {
            add.add(Category.REPETITION, Severity.INFO, null, null,
                    "Phrase repeated " + p.occurrences() + " times across chapters " + p.chapters(), p.phrase());
        }
        List<QualityReport.ChapterLength> lengths = lengths(ebook, printed, at, pages, add);

        findings.sort(Comparator.comparing(Finding::severity)
                .thenComparing(f -> f.chapter() == null ? Integer.MAX_VALUE : f.chapter()));
        int errors = (int) findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        int warnings = (int) findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
        return new QualityReport(Instant.now().toString(), pages, ebook.getPlannedPages(), errors, warnings,
                errors == 0, List.copyOf(findings), lengths, phrases);
    }

    @FunctionalInterface
    private interface Adder {
        void add(Category category, Severity severity, Integer chapter, Integer page, String message, String excerpt);
    }

    // ---- 1. Incomplete units ----------------------------------------------------

    private static void incompleteUnits(List<EbookChapter> inBook, List<EbookChapter> printed, Locator at, Adder add) {
        for (EbookChapter c : inBook) {
            if (c.getContent() == null || c.getContent().isBlank()) {
                add.add(Category.INCOMPLETE_UNIT, Severity.ERROR, c.getChapterNumber(), null,
                        "Chapter is in the outline but has no content"
                                + (c.getStatus() == ChapterStatus.FAILED && c.getGenerationError() != null
                                ? " (" + c.getGenerationError() + ")" : ""), null);
            }
        }
        for (int i = 0; i < printed.size(); i++) {
            EbookChapter c = printed.get(i);
            boolean last = i == printed.size() - 1;
            for (CompletenessCheck.Problem p : CompletenessCheck.inspect(c.getContent(), last)) {
                add.add(Category.INCOMPLETE_UNIT, Severity.ERROR, c.getChapterNumber(),
                        p.kind() == CompletenessCheck.Kind.ANNOUNCED_BLOCK_MISSING
                                ? at.pageOfText(c.getChapterNumber(), p.excerpt())
                                : at.lastPage(c.getChapterNumber()),
                        "Chapter " + p.describe().replaceFirst(" \\(\".*$", ""), p.excerpt());
            }
        }
    }

    // ---- Renderer notes (2, 4) ------------------------------------------------

    private static void rendererNotes(RenderContext ctx, Locator at, Adder add) {
        if (ctx == null) {
            return;
        }
        for (RenderContext.Note n : ctx.notes()) {
            Integer page = n.qaRef() == null ? at.firstPage(n.chapter()) : at.pageOfRef(n.qaRef());
            switch (n.type()) {
                case EMPTY_HEADING_REMOVED -> add.add(Category.EMPTY_HEADING, Severity.WARNING, n.chapter(), page,
                        "Heading had no content and was left out of the book; the section it announced is missing",
                        n.excerpt());
                case DUPLICATE_REMOVED -> add.add(Category.DUPLICATE_BLOCK, Severity.WARNING, n.chapter(), page,
                        n.message(), n.excerpt());
                case DUPLICATE_VERBATIM -> add.add(Category.DUPLICATE_BLOCK, Severity.INFO, n.chapter(), page,
                        n.message(), n.excerpt());
                case VERBATIM_SCALED -> add.add(Category.VERBATIM_OVERFLOW, Severity.INFO, n.chapter(), page,
                        n.message(), n.excerpt());
                case VERBATIM_WRAPPED -> add.add(Category.VERBATIM_OVERFLOW, Severity.WARNING, n.chapter(), page,
                        n.message(), n.excerpt());
            }
        }
    }

    // ---- 2. Structure of the final book --------------------------------------

    private static void structure(List<EbookChapter> printed, Locator at, Adder add) {
        Map<String, Integer> seen = new HashMap<>();
        for (EbookChapter c : printed) {
            int n = c.getChapterNumber();
            Element body = at.body(n);
            if (body == null) {
                continue;
            }
            for (Element h : body.select("h2, h3, h4, h5, h6")) {
                Element next = nextContent(h);
                if (next == null || (next.tagName().matches("h[1-6]")
                        && next.tagName().charAt(1) <= h.tagName().charAt(1))) {
                    add.add(Category.EMPTY_HEADING, Severity.ERROR, n, at.pageOf(h),
                            "Heading with no content under it", h.text());
                }
            }
            for (Element e : body.select("p, li")) {
                if (!e.select("img, pre, table").isEmpty()) {
                    continue;
                }
                String text = e.text();
                if (e.tagName().equals("li") && text.isBlank() && e.select("img").isEmpty()) {
                    add.add(Category.ORPHAN_LIST_MARKER, Severity.ERROR, n, at.pageOf(e),
                            "Empty list item (a number or bullet with nothing after it)", null);
                } else if (!e.tagName().equals("li") && ORPHAN_MARKER.matcher(text).matches()
                        && e.children().isEmpty()) {
                    add.add(Category.ORPHAN_LIST_MARKER, Severity.ERROR, n, at.pageOf(e),
                            "A list number or bullet standing alone", text.strip());
                }
            }
            if (body.text().contains("CMPBLOCKPLACEHOLDER")) {
                add.add(Category.ORPHAN_LIST_MARKER, Severity.ERROR, n, at.firstPage(n),
                        "An internal layout marker is printed", null);
            }
            for (Element cmp : body.select("div.cmp")) {
                if (!cmp.parents().select("div.cmp").isEmpty()) {
                    continue;
                }
                String fp = EbookContentRenderer.fingerprint(cmp.text());
                if (fp.length() < EbookContentRenderer.MIN_DUPLICATE_BLOCK_CHARS) {
                    continue;
                }
                Integer first = seen.putIfAbsent(fp, n);
                if (first != null) {
                    add.add(Category.DUPLICATE_BLOCK, Severity.ERROR, n, at.pageOf(cmp),
                            "The same box is printed again (first in chapter " + first + ")", excerpt(cmp.text()));
                }
            }
        }
    }

    /** The next sibling that is content (a keep-together wrapper counts as its first child). */
    private static Element nextContent(Element e) {
        Element next = e.nextElementSibling();
        if (next == null && e.parent() != null && e.parent().hasClass("keep-with-next")) {
            next = e.parent().nextElementSibling();
        }
        while (next != null && next.hasClass("keep-with-next") && next.firstElementChild() != null) {
            next = next.firstElementChild();
        }
        return next;
    }

    // ---- 2. Recurring sections (book template) --------------------------------

    private static void template(Ebook ebook, List<EbookChapter> printed, Locator at, Adder add) {
        BookTemplate template = BookTemplate.fromJson(ebook.getChapterTemplateJson());
        Set<String> templated = new HashSet<>();
        if (template != null) {
            for (BookTemplate.Section s : template.sections()) {
                templated.add(BookTemplate.normalise(s.heading()));
            }
            for (EbookChapter c : printed) {
                for (BookTemplate.Section s : template.missingFrom(c.getContent())) {
                    add.add(Category.TEMPLATE_MISMATCH, Severity.WARNING, c.getChapterNumber(),
                            at.lastPage(c.getChapterNumber()),
                            "Recurring section of the book template is missing from this chapter", s.heading());
                }
            }
        }
        if (printed.size() < 3) {
            return;
        }
        // A section heading in at least half the chapters but not in all of them
        // is a recurring section decided at random — whatever the template says.
        Map<String, List<Integer>> where = new LinkedHashMap<>();
        Map<String, String> label = new HashMap<>();
        for (EbookChapter c : printed) {
            Set<String> inChapter = new HashSet<>();
            for (String line : c.getContent().split("\n")) {
                String t = line.strip();
                if (t.matches("^#{2,3}\\s+.*")) {
                    String heading = t.replaceFirst("^#+\\s*", "");
                    String key = BookTemplate.normalise(heading);
                    if (!key.isEmpty() && inChapter.add(key)) {
                        label.putIfAbsent(key, heading);
                        where.computeIfAbsent(key, k -> new ArrayList<>()).add(c.getChapterNumber());
                    }
                }
            }
        }
        where.forEach((key, chapters) -> {
            if (templated.contains(key) || chapters.size() < 2 || chapters.size() * 2 < printed.size()
                    || chapters.size() == printed.size()) {
                return;
            }
            List<Integer> missing = printed.stream().map(EbookChapter::getChapterNumber)
                    .filter(n -> !chapters.contains(n)).toList();
            add.add(Category.TEMPLATE_MISMATCH, Severity.WARNING, null, null,
                    "Recurring section appears in chapters " + chapters + " but not in " + missing
                            + "; recurring sections should be in every chapter or in none",
                    label.get(key));
        });
    }

    // ---- 3. Internal data reaching the reader ----------------------------------

    /** Printed texts outside the running prose: checked for being cut off. */
    private static final String PARATEXT = ".chapter-intro, .opener-statement, .chapter-title, .opener-title, "
            + ".toc-title, figcaption, .cmp-title, .chapter-body h2, .chapter-body h3, .chapter-body h4, "
            + ".book-subtitle, .book-title";
    /**
     * Printed texts long enough to be a pasted brief or note: checked for
     * matching an internal field. (Titles and headings are left out — they
     * naturally share words with the brief that planned them.)
     */
    private static final String LEAK_PRONE = ".chapter-intro, .opener-statement, figcaption, .book-subtitle";

    private static void leaks(Ebook ebook, List<EbookChapter> chapters, Locator at, Adder add) {
        List<String> internal = new ArrayList<>();
        for (EbookChapter c : chapters) {
            internal.add(c.getDescription());
            internal.add(c.getSummary());
            internal.add(c.getCoveredTopics());
        }
        internal.add(ebook.getWritingGuidelines());
        Set<String> grams = new HashSet<>();
        List<String> internalNorm = new ArrayList<>();
        for (String s : internal) {
            if (s != null && !s.isBlank()) {
                String norm = EbookContentRenderer.fingerprint(s);
                internalNorm.add(norm);
                grams.addAll(ngrams(norm, 4));
            }
        }
        Element root = at.doc == null ? null : at.doc.body();
        if (root == null) {
            return;
        }
        for (Element e : root.select(PARATEXT)) {
            String text = e.text().strip();
            if (text.endsWith("…") || text.endsWith("...")) {
                add.add(Category.TRUNCATED_READER_TEXT, Severity.WARNING, at.chapterOf(e), at.pageOf(e),
                        "Reader text ends in an ellipsis (looks cut off)", text);
            }
        }
        List<Element> candidates = new ArrayList<>(root.select(LEAK_PRONE));
        // A chapter's first paragraph is where a brief pasted as an intro would sit.
        for (Element body : root.select(".chapter-body")) {
            Element p = body.selectFirst("p");
            if (p != null) {
                candidates.add(p);
            }
        }
        for (Element e : candidates) {
            String text = e.text().strip();
            Integer chapter = at.chapterOf(e);
            String norm = EbookContentRenderer.fingerprint(text);
            if (norm.length() < 25) {
                continue;
            }
            boolean copied = internalNorm.stream().anyMatch(s -> s.contains(norm));
            List<String> own = ngrams(norm, 4);
            long shared = own.stream().filter(grams::contains).count();
            if (copied || (own.size() >= 4 && shared >= LEAK_SHARE * own.size())) {
                add.add(Category.INTERNAL_LEAK, Severity.ERROR, chapter, at.pageOf(e),
                        "Printed text matches an internal field (a brief, summary or note written for the model)",
                        excerpt(text));
            }
        }
    }

    // ---- 5. Repetition ------------------------------------------------------------

    private static void repeatedTopics(List<EbookChapter> printed, Locator at, Adder add) {
        TopicRegistry.repeated(printed).forEach((topic, chapters) ->
                add.add(Category.REPETITION, Severity.WARNING, chapters.get(chapters.size() - 1),
                        at.firstPage(chapters.get(chapters.size() - 1)),
                        "Explained in chapters " + chapters + "; later chapters should refer back to chapter "
                                + chapters.get(0), topic));
    }

    /**
     * The phrases of {@link #PHRASE_WORDS} words repeated most across chapters,
     * read from the printed prose (verbatim blocks excluded). A phrase must
     * appear in at least two chapters (three in a book of six or more);
     * phrases made only of very short words are ignored, and a phrase
     * overlapping one already listed is not listed again.
     */
    static List<QualityReport.RepeatedPhrase> repeatedPhrases(List<EbookChapter> printed, Locator at) {
        Map<String, Integer> occurrences = new HashMap<>();
        Map<String, Set<Integer>> chaptersOf = new HashMap<>();
        for (EbookChapter c : printed) {
            String text = proseText(c, at);
            List<String> words = List.of(EbookContentRenderer.fingerprint(text).split(" "));
            for (int i = 0; i + PHRASE_WORDS <= words.size(); i++) {
                List<String> window = words.subList(i, i + PHRASE_WORDS);
                if (window.stream().allMatch(w -> w.length() <= 3)) {
                    continue;
                }
                String phrase = String.join(" ", window);
                occurrences.merge(phrase, 1, Integer::sum);
                chaptersOf.computeIfAbsent(phrase, k -> new java.util.TreeSet<>()).add(c.getChapterNumber());
            }
        }
        int minChapters = printed.size() >= 6 ? 3 : 2;
        List<String> ranked = occurrences.keySet().stream()
                .filter(p -> chaptersOf.get(p).size() >= minChapters)
                .sorted(Comparator.comparing((String p) -> chaptersOf.get(p).size()).reversed()
                        .thenComparing(p -> -occurrences.get(p))
                        .thenComparing(p -> p))
                .toList();
        List<QualityReport.RepeatedPhrase> out = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (String p : ranked) {
            List<String> sub = ngrams(p, PHRASE_WORDS - 1);
            if (sub.stream().anyMatch(covered::contains)) {
                continue;
            }
            covered.addAll(sub);
            out.add(new QualityReport.RepeatedPhrase(p, occurrences.get(p), List.copyOf(chaptersOf.get(p))));
            if (out.size() == TOP_PHRASES) {
                break;
            }
        }
        return out;
    }

    private static String proseText(EbookChapter c, Locator at) {
        Element body = at.body(c.getChapterNumber());
        if (body != null) {
            Element copy = body.clone();
            copy.select("pre, code, table").remove();
            return copy.text();
        }
        return c.getContent().replaceAll("(?s)```.*?```", " ");
    }

    // ---- 6. Length vs budget ----------------------------------------------------

    private static List<QualityReport.ChapterLength> lengths(Ebook ebook, List<EbookChapter> printed, Locator at,
                                                             int pages, Adder add) {
        List<QualityReport.ChapterLength> out = new ArrayList<>();
        for (EbookChapter c : printed) {
            int planned = Math.max(0, c.getApproxPages());
            int actual = at.pageCount(c.getChapterNumber());
            if (actual <= 0) {
                actual = (int) Math.ceil(LengthMeter.pages(c.getContent()));
            }
            double deviation = planned > 0 ? (actual - planned) / (double) planned : 0;
            out.add(new QualityReport.ChapterLength(c.getChapterNumber(), c.getTitle(), planned, actual,
                    Math.round(deviation * 1000) / 1000.0));
            if (planned > 0 && Math.abs(deviation) > CHAPTER_TOLERANCE && Math.abs(actual - planned) >= MIN_PAGE_GAP) {
                add.add(Category.LENGTH_BUDGET, Severity.WARNING, c.getChapterNumber(), at.firstPage(c.getChapterNumber()),
                        String.format(Locale.ROOT, "Chapter planned at %d pages, rendered at %d (%+d%%)",
                                planned, actual, Math.round(deviation * 100)), null);
            }
        }
        Integer plannedBook = ebook.getPlannedPages();
        if (plannedBook != null && plannedBook > 0 && pages > 0) {
            double deviation = (pages - plannedBook) / (double) plannedBook;
            if (Math.abs(deviation) > BOOK_TOLERANCE) {
                add.add(Category.LENGTH_BUDGET, deviation > 2 * BOOK_TOLERANCE ? Severity.ERROR : Severity.WARNING,
                        null, null, String.format(Locale.ROOT, "Book planned at %d pages, rendered at %d (%+d%%)",
                                plannedBook, pages, Math.round(deviation * 100)), null);
            }
        }
        return out;
    }

    // ---- helpers ------------------------------------------------------------------

    static List<String> ngrams(String normalised, int n) {
        String[] words = normalised.isBlank() ? new String[0] : normalised.split(" ");
        List<String> out = new ArrayList<>();
        for (int i = 0; i + n <= words.length; i++) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + n)));
        }
        return out;
    }

    private static String excerpt(String text) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    /**
     * Where things are in the typeset book: chapter bodies in the final HTML and
     * pages from the laid-out geometry (element → layout id → page).
     */
    static final class Locator {
        final Document doc;
        private final Map<String, Integer> pageById = new HashMap<>();
        private final Map<Integer, int[]> chapterPages = new HashMap<>();

        Locator(Document doc, LayoutSnapshot layout) {
            this.doc = doc;
            if (layout == null) {
                return;
            }
            List<LayoutSnapshot.Page> pages = layout.pages();
            for (LayoutSnapshot.Block b : layout.blocks()) {
                int page = pageAt(pages, b.rect().top());
                if (page <= 0) {
                    continue;
                }
                pageById.putIfAbsent(b.id(), page);
                if (b.chapter() != null && b.chapter().startsWith("chapter-") && !b.decorative()) {
                    try {
                        int n = Integer.parseInt(b.chapter().substring("chapter-".length()));
                        chapterPages.merge(n, new int[]{page, page},
                                (a, x) -> new int[]{Math.min(a[0], x[0]), Math.max(a[1], x[1])});
                    } catch (NumberFormatException ignored) {
                        // not a chapter anchor
                    }
                }
            }
        }

        private static int pageAt(List<LayoutSnapshot.Page> pages, float y) {
            for (LayoutSnapshot.Page p : pages) {
                if (y >= p.pageTop() && y < p.pageBottom()) {
                    return p.number();
                }
            }
            return -1;
        }

        /** The chapter's body element in the final HTML (band or full-page opener), or null. */
        Element body(int chapter) {
            if (doc == null) {
                return null;
            }
            Element anchor = doc.getElementById("chapter-" + chapter);
            if (anchor == null) {
                return null;
            }
            Element body = anchor.selectFirst(".chapter-body");
            if (body == null && anchor.nextElementSibling() != null) {
                body = anchor.nextElementSibling().selectFirst(".chapter-body");
            }
            return body;
        }

        Integer chapterOf(Element e) {
            Element cur = e;
            while (cur != null) {
                String id = cur.id();
                if (id.startsWith("chapter-")) {
                    try {
                        return Integer.parseInt(id.substring("chapter-".length()));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                Element prev = cur.previousElementSibling();
                if (cur.hasClass("chapter-continued") && prev != null && prev.id().startsWith("chapter-")) {
                    cur = prev;
                    continue;
                }
                cur = cur.parent();
            }
            return null;
        }

        Integer pageOf(Element e) {
            for (Element cur = e; cur != null; cur = cur.parent()) {
                Integer page = pageById.get(cur.attr(LayoutSnapshot.ID_ATTR));
                if (page != null) {
                    return page;
                }
            }
            return null;
        }

        Integer pageOfRef(String qaRef) {
            if (doc == null || qaRef == null) {
                return null;
            }
            Element e = doc.selectFirst("[" + RenderContext.QA_ATTR + "=" + qaRef + "]");
            return e == null ? null : pageOf(e);
        }

        /** The page of the first block in the chapter containing {@code excerpt}'s text. */
        Integer pageOfText(int chapter, String excerpt) {
            Element body = body(chapter);
            if (body != null && excerpt != null) {
                String needle = EbookContentRenderer.fingerprint(excerpt);
                for (Element p : body.select("p, li")) {
                    if (!needle.isEmpty() && EbookContentRenderer.fingerprint(p.text()).contains(needle)) {
                        return pageOf(p);
                    }
                }
            }
            return lastPage(chapter);
        }

        Integer firstPage(int chapter) {
            int[] range = chapterPages.get(chapter);
            return range == null ? null : range[0];
        }

        Integer lastPage(int chapter) {
            int[] range = chapterPages.get(chapter);
            return range == null ? null : range[1];
        }

        /** Pages the chapter spans in the PDF (its opener included), or 0 when unknown. */
        int pageCount(int chapter) {
            int[] range = chapterPages.get(chapter);
            return range == null ? 0 : range[1] - range[0] + 1;
        }
    }
}
