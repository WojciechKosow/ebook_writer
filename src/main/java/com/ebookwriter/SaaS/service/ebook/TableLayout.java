package com.ebookwriter.SaaS.service.ebook;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sizes table columns to the text column, from the table's own content, and
 * decides how each table meets a page break ({@link #paginate}, {@link #guardStarts}).
 *
 * <p>Left to itself the renderer uses automatic table layout, where a column can
 * never be narrower than its longest unbreakable word. Technical tables are full
 * of those ({@code MethodArgumentNotValidException}, {@code EMAIL_ALREADY_REGISTERED},
 * property keys), so the columns' minimums add up to more than the page and the
 * table runs off it. Instead, every table gets an explicit {@code <colgroup>}
 * (rendered with {@code table-layout: fixed}, so the table is exactly the column
 * width) whose proportions follow the content, the way a browser's automatic
 * layout would if it were forced to fit:
 * <ol>
 *   <li>each column has a <b>minimum</b> (its widest unbreakable piece, found
 *       with the renderer's own line breaker — a word, a path segment, an
 *       identifier fragment — plus cell padding) and a <b>natural</b> width (its
 *       widest cell set on one line), measured with the book's own fonts at the
 *       sizes the stylesheet gives table text, header cells and code chips;</li>
 *   <li>if every column fits at its natural width, the spare width is shared in
 *       proportion to the natural widths;</li>
 *   <li>otherwise every column gets its minimum, and the remaining width goes to
 *       the columns in proportion to how much more they would like (natural −
 *       minimum) — short categorical columns (METHOD, AUTH) stay narrow, prose
 *       columns absorb the wrapping;</li>
 *   <li>if even the minimums don't fit, the columns that fit within an even share
 *       keep their minimum and the widest columns are capped to one common
 *       width; there the stylesheet's {@code word-wrap: break-word} breaks the
 *       longest tokens.</li>
 * </ol>
 * A single enormous token is capped so it can't starve the other columns, and a
 * prose column never drops below a readable floor. Code in cells first gets
 * invisible break points between its words ({@link #addCodeBreaks}), so most
 * identifiers wrap at a word boundary rather than needing that last resort.
 */
final class TableLayout {

    /** No column's unbreakable minimum may claim more than this share of the table. */
    static final double MAX_MIN_SHARE = 0.42;

    /** A column holding prose keeps at least this share (or its natural width, if smaller). */
    static final double READABLE_FLOOR_SHARE = 0.14;

    // Metrics mirrored from pdf/ebook.css (table, th, td, code).
    private static final float BODY_PT = 10f;          // table { font-size: 10pt }
    private static final float HEADER_PT = 8.5f;       // th { font-size: 8.5pt }
    private static final float HEADER_TRACKING_EM = 0.06f; // th { letter-spacing: 0.06em }
    private static final float CODE_PT = 9.5f;         // code { font-size: 9.5pt }
    private static final float CODE_PAD_EM = 0.3f;     // code { padding: 0.05em 0.3em }
    private static final float CELL_PAD_EM = 0.65f;    // th, td { padding: 0.45em 0.65em }
    private static final float BORDER_PT = 0.75f;      // th, td { border: 1px }
    /** Headroom for the renderer's rounding of borders and percentage widths. */
    private static final float SLACK_PT = 2f;

    private enum Face {
        SERIF("LiberationSerif-Regular.ttf"),
        SERIF_BOLD("LiberationSerif-Bold.ttf"),
        SANS_BOLD("LiberationSans-Bold.ttf"),
        MONO("LiberationMono-Regular.ttf");

        final String file;

        Face(String file) {
            this.file = file;
        }
    }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static volatile Map<Face, Font> fonts;

    private TableLayout() {
    }

    /** A run of text set in one face (and, for code, one chip). */
    private record Run(String text, Face face, float sizePt, float trackingPt, boolean chip) {
    }

    /** Shortest fragment a code token is split into, so it never wraps into crumbs. */
    static final int MIN_FRAGMENT = 3;

    /** Give the table an explicit, content-proportioned colgroup that fits {@code availablePt}. */
    static void apply(Element table, float availablePt) {
        addCodeBreaks(table);
        List<List<Element>> rows = new ArrayList<>();
        int cols = 0;
        for (Element tr : table.select("tr")) {
            if (tr.closest("table") != table) {
                continue; // a nested table sizes itself
            }
            List<Element> cells = new ArrayList<>(tr.select("> th, > td"));
            rows.add(cells);
            cols = Math.max(cols, cells.size());
        }
        if (cols == 0) {
            return;
        }
        double[] min = new double[cols];
        double[] max = new double[cols];
        for (List<Element> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                Element cell = row.get(c);
                List<Run> runs = new ArrayList<>();
                boolean header = cell.tagName().equals("th");
                collect(cell, header, false, false, runs);
                float pad = 2 * CELL_PAD_EM * (header ? HEADER_PT : BODY_PT) + BORDER_PT + SLACK_PT;
                min[c] = Math.max(min[c], widestToken(runs) + pad);
                max[c] = Math.max(max[c], naturalWidth(runs) + pad);
            }
        }
        double[] widths = distribute(min, max, availablePt);

        table.select("> colgroup").remove();
        Element colgroup = new Element("colgroup");
        double used = 0;
        for (int c = 0; c < cols; c++) {
            double pct = c == cols - 1 ? 100 - used : Math.round(widths[c] / availablePt * 1000) / 10.0;
            used += pct;
            colgroup.appendElement("col").attr("style", String.format(Locale.ROOT, "width: %.1f%%", pct));
        }
        table.prependChild(colgroup);

        paginate(table, rows, widths, availablePt);
    }

    // ---- Vertical pagination ------------------------------------------------------

    /** A table at most this share of a page tall is kept whole: it moves to the next page rather than split. */
    static final double WHOLE_TABLE_SHARE = 0.3;

    /** Marks a table kept whole (stylesheet: {@code page-break-inside: avoid}). */
    static final String WHOLE = "table--whole";

    /** The estimated height (pt) of the table's start: its header plus first body row. */
    static final String START_ATTR = "data-start-pt";

    /** The estimated height (pt) of the whole table. */
    static final String HEIGHT_ATTR = "data-height-pt";

    /**
     * Decide how the table meets a page break. The renderer repeats the header
     * on every continuation page and keeps rows whole (stylesheet); what it
     * can't do reliably is decide where the table should <em>start</em>: left
     * alone it sets the header at a page foot and pushes the first row on, and a
     * table it had to move itself loses its rows' keep-together. So the start is
     * decided here, from the content:
     * <ul>
     *   <li>a <b>short</b> table (≤ {@link #WHOLE_TABLE_SHARE} of a page) is kept
     *       whole — splitting three rows over two pages helps nobody;</li>
     *   <li>a longer table may split, but only starts on a page with room for its
     *       header and first row ({@code -fs-page-break-min-height}); otherwise it
     *       starts on the next page. After that it fills each page with whole
     *       rows and continues under a repeated header.</li>
     * </ul>
     */
    private static void paginate(Element table, List<List<Element>> rows, double[] widths, float availablePt) {
        double header = 0;
        double first = -1;
        double total = TABLE_MARGIN_PT;
        for (List<Element> row : rows) {
            double h = rowHeight(row, widths, availablePt);
            total += h;
            boolean headerRow = row.stream().allMatch(c -> c.tagName().equals("th"));
            if (headerRow && first < 0) {
                header += h;
            } else if (first < 0) {
                first = h;
            }
        }
        double start = TABLE_MARGIN_PT + header + Math.max(first, 0) + START_HEADROOM_PT;
        table.attr(START_ATTR, String.format(Locale.ROOT, "%.0f", start));
        table.attr(HEIGHT_ATTR, String.format(Locale.ROOT, "%.0f", total));
        if (total <= WHOLE_TABLE_SHARE * PageGeometry.book().contentHeightPt()) {
            table.addClass(WHOLE);
        }
    }

    /**
     * Guard the start of every splittable table that isn't already the end of a
     * heading/lead-in group (which carries its own guard): wrap it in a block
     * with the minimum height. The guard must sit on a wrapper — on the table
     * itself the renderer applies it to the rows, moving them on and leaving
     * the header behind, the very break it is meant to prevent. A table that
     * opens the chapter ({@code opener}) starts under the chapter opener as is.
     */
    static void guardStarts(Element root, Element opener) {
        for (Element table : root.select("table")) {
            Element parent = table.parent();
            if (table == opener || table.hasClass(WHOLE) || startHeight(table) <= 0
                    || (parent != null && parent.hasClass(EbookContentRenderer.KEEP_START))) {
                continue;
            }
            Element wrapper = new Element("div").addClass("table-start")
                    .attr("style", minHeightStyle(startHeight(table)));
            table.before(wrapper);
            wrapper.appendChild(table);
        }
    }

    /** No guard asks for more than this share of a page: a start that can't fit any page guards nothing. */
    static final double MAX_GUARD_SHARE = 0.85;

    /**
     * The CSS that keeps a block off a page with less than {@code pt} left —
     * capped below a full page, because a guard no page can satisfy makes the
     * renderer break, find the fresh page still too short, and break again,
     * leaving a blank page behind.
     */
    static String minHeightStyle(double pt) {
        double capped = Math.min(pt, MAX_GUARD_SHARE * PageGeometry.book().contentHeightPt());
        return String.format(Locale.ROOT, "-fs-page-break-min-height: %.0fpt", capped);
    }

    /** Estimated start height of a table sized by {@link #apply}, or 0 if it wasn't. */
    static double startHeight(Element table) {
        return number(table.attr(START_ATTR));
    }

    /** Estimated height of a whole table sized by {@link #apply}, or 0 if it wasn't. */
    static double wholeHeight(Element table) {
        return number(table.attr(HEIGHT_ATTR));
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // table { margin: 1em 0 } at 10pt; the header row's line box; th, td { padding: 0.45em … }
    private static final double TABLE_MARGIN_PT = 10;
    private static final double LINE_HEIGHT = 1.52;     // body { line-height: 1.52 }
    private static final double CELL_VPAD_EM = 0.45;
    /** Estimates err low on wrapping; keep a little room so "fits" means fits. */
    private static final double START_HEADROOM_PT = 16;

    private static double rowHeight(List<Element> row, double[] widths, float availablePt) {
        double tallest = 0;
        for (int c = 0; c < row.size() && c < widths.length; c++) {
            Element cell = row.get(c);
            boolean header = cell.tagName().equals("th");
            float size = header ? HEADER_PT : BODY_PT;
            List<Run> runs = new ArrayList<>();
            collect(cell, header, false, false, runs);
            double inner = widths[c] - 2 * CELL_PAD_EM * size - BORDER_PT;
            double h = lines(runs, inner) * size * LINE_HEIGHT + 2 * CELL_VPAD_EM * size + BORDER_PT;
            tallest = Math.max(tallest, h);
        }
        return tallest;
    }

    /** Lines the runs wrap to at {@code width}: greedy fill over the renderer's break opportunities. */
    private static int lines(List<Run> runs, double width) {
        List<double[]> pieces = pieces(runs); // {width without trailing space, trailing space}
        int lines = 1;
        double line = 0;
        for (double[] p : pieces) {
            if (p[0] < 0) { // explicit <br>
                lines++;
                line = 0;
                continue;
            }
            if (line > 0 && line + p[0] > width) {
                lines++;
                line = 0;
            }
            // A piece wider than the column is broken (word-wrap: break-word).
            if (p[0] > width && width > 0) {
                lines += (int) Math.floor(p[0] / width);
                line = p[0] % width;
            } else {
                line += p[0];
            }
            line += p[1];
        }
        return lines;
    }

    /** Typefaces the book sets text in, for {@link #textLines}. */
    enum Typeface { SERIF, SANS_BOLD, MONO }

    /**
     * Lines {@code text} wraps to in a column {@code widthPt} wide, set in
     * {@code face} at {@code sizePt} — measured with the bundled fonts and the
     * renderer's own break opportunities. Shared with {@link BlockMetrics}.
     */
    static int textLines(String text, Typeface face, float sizePt, double widthPt) {
        Face f = switch (face) {
            case SERIF -> Face.SERIF;
            case SANS_BOLD -> Face.SANS_BOLD;
            case MONO -> Face.MONO;
        };
        return lines(List.of(new Run(text.replace('\n', ' '), f, sizePt, 0, false)), widthPt);
    }

    /**
     * Width (pt) of {@code text} on one line, set in {@code face} at
     * {@code sizePt}, measured with the bundled fonts. Used for blocks whose
     * lines must not wrap ({@link VerbatimLayout}).
     */
    static double textWidth(String text, Typeface face, float sizePt) {
        Face f = switch (face) {
            case SERIF -> Face.SERIF;
            case SANS_BOLD -> Face.SANS_BOLD;
            case MONO -> Face.MONO;
        };
        return measure(text, new Run(text, f, sizePt, 0, false));
    }

    private static List<double[]> pieces(List<Run> runs) {
        StringBuilder all = new StringBuilder();
        List<Integer> owner = new ArrayList<>();
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            String t = runs.get(i).text();
            if (t.equals("\n")) {
                flushPieces(all, owner, runs, out);
                out.add(new double[]{-1, 0});
                continue;
            }
            all.append(t);
            for (int k = 0; k < t.length(); k++) {
                owner.add(i);
            }
        }
        flushPieces(all, owner, runs, out);
        return out;
    }

    private static void flushPieces(StringBuilder all, List<Integer> owner, List<Run> runs, List<double[]> out) {
        String text = all.toString();
        if (!text.isEmpty()) {
            CodeBreakLineBreaker breaker = new CodeBreakLineBreaker();
            breaker.setText(text);
            int start = 0;
            for (int end = breaker.next(); start < text.length(); end = breaker.next()) {
                if (end == java.text.BreakIterator.DONE || end > text.length()) {
                    end = text.length();
                }
                if (end <= start) {
                    continue;
                }
                double full = measurePiece(text, owner, runs, start, end, false);
                double trimmed = measurePiece(text, owner, runs, start, end, true);
                out.add(new double[]{trimmed, full - trimmed});
                start = end;
            }
        }
        all.setLength(0);
        owner.clear();
    }

    /** Column widths (pt) summing to {@code available}; see the class comment for the rules. */
    static double[] distribute(double[] minIn, double[] maxIn, double available) {
        int n = minIn.length;
        double[] min = new double[n];
        double[] max = new double[n];
        double sumMin = 0;
        double sumMax = 0;
        for (int c = 0; c < n; c++) {
            max[c] = Math.max(maxIn[c], 1);
            min[c] = Math.min(Math.min(minIn[c], max[c]), available * MAX_MIN_SHARE);
            // Prose wraps, but not into a sliver: keep a readable floor.
            min[c] = Math.max(min[c], Math.min(max[c], available * READABLE_FLOOR_SHARE));
            sumMin += min[c];
            sumMax += max[c];
        }
        double[] w = new double[n];
        if (sumMax <= available) {
            for (int c = 0; c < n; c++) {
                w[c] = max[c] / sumMax * available;
            }
        } else if (sumMin <= available) {
            double spare = available - sumMin;
            double want = sumMax - sumMin;
            for (int c = 0; c < n; c++) {
                w[c] = min[c] + (want <= 0 ? 0 : (max[c] - min[c]) / want * spare);
            }
        } else {
            // Even the minimums don't fit. Columns that fit within an even share
            // keep their minimum (METHOD, AUTH stay whole words); the deficit comes
            // only out of the widest columns, which are capped to one common width
            // and break their longest tokens.
            double[] sorted = min.clone();
            java.util.Arrays.sort(sorted);
            double rest = available;
            double cap = available / n;
            for (int i = 0; i < n; i++) {
                double share = rest / (n - i);
                if (sorted[i] > share) {
                    cap = share;
                    break;
                }
                rest -= sorted[i];
            }
            for (int c = 0; c < n; c++) {
                w[c] = Math.min(min[c], cap);
            }
        }
        return w;
    }

    // ---- Wrapping code ----------------------------------------------------------

    /**
     * The renderer wraps code at {@code .} {@code -} and {@code /} (its line
     * breaker is URL-aware), but an identifier like
     * {@code MethodArgumentNotValidException} or {@code EMAIL_ALREADY_REGISTERED}
     * has no break at all: it either forces its column wide or is cut at an
     * arbitrary letter. Inside table cells, code gets the break points a reader
     * expects — between camelCase words, after {@code _}, before a generic
     * {@code <} — as zero-width spaces, which {@link CodeBreakLineBreaker} (and
     * every browser) treats as an invisible break. Only cells are touched; code
     * in running text wraps exactly as before.
     */
    static void addCodeBreaks(Element table) {
        for (Element code : table.select("th code, td code")) {
            for (TextNode t : code.textNodes()) {
                t.text(codeBreaks(t.getWholeText()));
            }
        }
    }

    static String codeBreaks(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        int fragment = 0; // characters since the last break opportunity
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (fragment >= MIN_FRAGMENT && breaksBefore(text, i) && remainingRun(text, i) >= MIN_FRAGMENT) {
                out.append(CodeBreakLineBreaker.ZWSP);
                fragment = 0;
            }
            out.append(c);
            boolean natural = Character.isWhitespace(c) || c == '.' || c == '-' || c == '/'
                    || c == CodeBreakLineBreaker.ZWSP;
            fragment = natural ? 0 : fragment + 1;
        }
        return out.toString();
    }

    private static boolean breaksBefore(String s, int i) {
        char prev = s.charAt(i - 1);
        char c = s.charAt(i);
        if (prev == '_' && Character.isLetterOrDigit(c)) {
            return true;
        }
        if (c == '<' && Character.isLetterOrDigit(prev)) {
            return true;
        }
        if (Character.isUpperCase(c) && (Character.isLowerCase(prev) || Character.isDigit(prev))) {
            return true; // fooBar, v2Api
        }
        // HTTPServer -> HTTP|Server
        return Character.isUpperCase(c) && Character.isUpperCase(prev)
                && i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
    }

    /** Characters left in this identifier from {@code i} (a break needs a real fragment after it). */
    private static int remainingRun(String s, int i) {
        int n = 0;
        while (i + n < s.length() && (Character.isLetterOrDigit(s.charAt(i + n)) || s.charAt(i + n) == '_'
                || s.charAt(i + n) == '<' || s.charAt(i + n) == '>')) {
            n++;
        }
        return n;
    }

    // ---- Measuring ------------------------------------------------------------

    private static void collect(Node node, boolean header, boolean bold, boolean code, List<Run> out) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                String text = t.getWholeText().replace('\n', ' ');
                if (text.isEmpty()) {
                    continue;
                }
                if (header) {
                    out.add(new Run(text.toUpperCase(Locale.ROOT), Face.SANS_BOLD, HEADER_PT,
                            HEADER_TRACKING_EM * HEADER_PT, false));
                } else if (code) {
                    out.add(new Run(text, Face.MONO, CODE_PT, 0, true));
                } else {
                    out.add(new Run(text, bold ? Face.SERIF_BOLD : Face.SERIF, BODY_PT, 0, false));
                }
            } else if (child instanceof Element e) {
                if (e.tagName().equals("br")) {
                    out.add(new Run("\n", Face.SERIF, BODY_PT, 0, false));
                    continue;
                }
                String tag = e.tagName();
                collect(e, header, bold || tag.equals("strong") || tag.equals("b"),
                        code || tag.equals("code"), out);
            }
        }
    }

    /** The cell set on one line (the widest line, if it has explicit breaks). */
    private static double naturalWidth(List<Run> runs) {
        double widest = 0;
        double line = 0;
        for (Run r : runs) {
            if (r.text().equals("\n")) {
                widest = Math.max(widest, line);
                line = 0;
                continue;
            }
            line += measure(r.text().replaceAll("\\s+", " "), r) + (r.chip() ? 2 * CODE_PAD_EM * CODE_PT : 0);
        }
        return Math.max(widest, line);
    }

    /**
     * The widest piece no line break can fall inside, found with the renderer's
     * own line breaker over the whole cell (so a token can span runs, as in
     * {@code `Order`.items}); a piece touching a code chip carries its padding.
     */
    private static double widestToken(List<Run> runs) {
        StringBuilder all = new StringBuilder();
        List<Integer> owner = new ArrayList<>(); // run index of each char
        for (int i = 0; i < runs.size(); i++) {
            String t = runs.get(i).text();
            all.append(t);
            for (int k = 0; k < t.length(); k++) {
                owner.add(i);
            }
        }
        String text = all.toString();
        CodeBreakLineBreaker breaker = new CodeBreakLineBreaker();
        breaker.setText(text);
        double widest = 0;
        int start = 0;
        for (int end = breaker.next(); start < text.length(); end = breaker.next()) {
            if (end == java.text.BreakIterator.DONE || end > text.length()) {
                end = text.length();
            }
            if (end <= start) {
                continue;
            }
            widest = Math.max(widest, measurePiece(text, owner, runs, start, end, true));
            start = end;
        }
        return widest;
    }

    /** Width of {@code text[start, end)} (without its trailing spaces if {@code trim}), run by run. */
    private static double measurePiece(String text, List<Integer> owner, List<Run> runs, int start, int end,
                                       boolean trim) {
        while (trim && end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        double w = 0;
        boolean chip = false;
        int i = start;
        while (i < end) {
            int run = owner.get(i);
            int j = i;
            while (j < end && owner.get(j) == run) {
                j++;
            }
            Run r = runs.get(run);
            w += measure(text.substring(i, j), r);
            chip |= r.chip();
            i = j;
        }
        return w + (chip ? 2 * CODE_PAD_EM * CODE_PT : 0);
    }

    private static double measure(String raw, Run run) {
        String text = raw.replace(String.valueOf(CodeBreakLineBreaker.ZWSP), "");
        if (text.isEmpty()) {
            return 0;
        }
        Font font = fonts().get(run.face());
        double w;
        if (font != null) {
            w = font.deriveFont(run.sizePt()).getStringBounds(text, FRC).getWidth();
        } else {
            // Metrics unavailable: an average advance per face is close enough.
            double em = switch (run.face()) {
                case MONO -> 0.60;
                case SANS_BOLD -> 0.68;
                default -> 0.48;
            };
            w = text.length() * em * run.sizePt();
        }
        return w + text.length() * run.trackingPt();
    }

    private static Map<Face, Font> fonts() {
        Map<Face, Font> f = fonts;
        if (f == null) {
            f = new EnumMap<>(Face.class);
            for (Face face : Face.values()) {
                try (InputStream in = TableLayout.class.getResourceAsStream("/fonts/" + face.file)) {
                    if (in != null) {
                        f.put(face, Font.createFont(Font.TRUETYPE_FONT, in));
                    }
                } catch (Exception | LinkageError | java.awt.AWTError e) {
                    // headless/font failure: fall back to average advances
                }
            }
            fonts = f;
        }
        return f;
    }
}
