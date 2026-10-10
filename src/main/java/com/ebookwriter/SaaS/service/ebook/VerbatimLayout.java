package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ebook.TableLayout.Typeface;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays out <b>verbatim blocks</b>: any block whose line breaks and indentation
 * carry meaning. That is one generic type with one rule — whitespace is
 * significant — and nothing here knows what the content is (source code in any
 * language, terminal output, configuration, a poem, an address, an ASCII table,
 * a list of ingredients…). Two presentations exist only as typography:
 * <ul>
 *   <li>{@code <pre>} (a fenced block): monospaced, in a panel;</li>
 *   <li>{@code <pre class="verbatim verbatim--text">} (a {@code :::verbatim}
 *       block): the book's text face, no panel.</li>
 * </ul>
 *
 * <p>Lines are never wrapped invisibly. The stylesheet sets verbatim blocks
 * {@code white-space: pre}; this class makes every line fit the column it sits in:
 * <ol>
 *   <li>a block whose longest line fits is left exactly as written;</li>
 *   <li>otherwise its font is reduced step by step, down to a readable
 *       minimum;</li>
 *   <li>a line that still does not fit is carried over <em>visibly</em>: the
 *       continuation starts on a new line under the line's own indentation,
 *       behind a continuation mark ({@value #MARKER}) set in a distinct colour
 *       ({@code .vcont}). The text of the line itself is never altered or
 *       dropped; only the mark and the hanging indent are added.</li>
 * </ol>
 * Every reduction and carry-over is noted in the {@link RenderContext} for the
 * author's quality report.
 *
 * <p>{@link #maxChars} turns the same geometry into the line-length limit the
 * writer is given, so most blocks never need either measure.
 */
public final class VerbatimLayout {

    /** Continuation mark printed before the carried-over part of an over-long line. */
    static final String MARKER = "→";
    /** Class of the continuation mark (and its hanging indent). */
    static final String CONTINUATION = "vcont";
    /** Classes of a verbatim block set in the text face (a {@code :::verbatim} block). */
    static final String TEXT_CLASS = "verbatim--text";

    static final float STEP_PT = 0.5f;
    /** Room kept free at the line end, so "fits" means fits after rounding. */
    static final double SAFETY_PT = 2;
    static final int TAB_WIDTH = 4;

    /**
     * Typography of a verbatim block, mirroring {@code pdf/ebook.css}: face,
     * normal size, the smallest size still comfortably readable, and the
     * horizontal padding (em) plus borders (pt) the block takes from its column.
     */
    record Style(Typeface face, float basePt, float minPt, double padEm, double borderPt) {
        double inset(float sizePt) {
            return padEm * sizePt + borderPt;
        }
    }

    /** {@code pre { font-size: 9pt; padding: 0.8em 1em; border: 1px }}. */
    static final Style MONO = new Style(Typeface.MONO, 9f, 7f, 2.0, 1.5);
    /** {@code pre.verbatim--text { font-size: 10.5pt; padding-left: 1.2em; border: 0 }}. */
    static final Style TEXT = new Style(Typeface.SERIF, 10.5f, 8.5f, 1.2, 0);

    private static final Pattern FONT_SIZE = Pattern.compile("font-size:\\s*([\\d.]+)pt");

    private VerbatimLayout() {
    }

    /**
     * Line-length limits for the writer, from the real page and fonts: the
     * longest line of a monospaced block in the text column, the same inside a
     * component box or list, and of a text-face verbatim block.
     */
    public record Limits(int monoChars, int monoNestedChars, int textChars) {
    }

    public static Limits limits() {
        float column = PageGeometry.book().contentWidthPt();
        return new Limits(maxChars(MONO, column), maxChars(MONO, column - 28f), maxChars(TEXT, column));
    }

    static Style style(Element pre) {
        return pre.hasClass(TEXT_CLASS) ? TEXT : MONO;
    }

    /** The font size the block is set in (its own style, else the stylesheet's). */
    static float fontSize(Element pre) {
        Matcher m = FONT_SIZE.matcher(pre.attr("style"));
        return m.find() ? Float.parseFloat(m.group(1)) : style(pre).basePt();
    }

    /**
     * The longest line, in characters, that fits a verbatim block of this style
     * at its normal size in a column {@code columnPt} wide — the limit the writer
     * is given. Measured on the bundled fonts with an average character.
     */
    static int maxChars(Style style, double columnPt) {
        String sample = "abcdefghijklmnopqrstuvwxyz ";
        double avg = TableLayout.textWidth(sample, style.face(), style.basePt()) / sample.length();
        double room = columnPt - style.inset(style.basePt()) - SAFETY_PT;
        return (int) Math.floor(room / Math.max(avg, 0.1));
    }

    /**
     * Fit a verbatim block into a column {@code availablePt} wide (the column
     * less any box or list it sits in): unchanged if it fits, else smaller, else
     * with visible carry-overs. See the class comment.
     */
    static void apply(Element pre, double availablePt, RenderContext ctx) {
        Element target = pre.selectFirst("> code");
        if (target == null) {
            target = pre;
        }
        String raw = target.wholeText();
        if (raw.endsWith("\n")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        String text = expandTabs(raw);
        String[] lines = text.split("\n", -1);
        Style style = style(pre);

        float size = style.basePt();
        while (size - STEP_PT >= style.minPt() - 1e-3 && widest(lines, style, size) > room(availablePt, style, size)) {
            size -= STEP_PT;
        }
        double room = room(availablePt, style, size);
        boolean overflows = widest(lines, style, size) > room;

        if (size < style.basePt()) {
            String ref = ref(pre, ctx);
            String current = pre.attr("style");
            String fs = String.format(Locale.ROOT, "font-size: %.1fpt", size);
            pre.attr("style", current.isBlank() ? fs : current + ";" + fs);
            ctx.note(RenderContext.NoteType.VERBATIM_SCALED,
                    String.format(Locale.ROOT, "Verbatim block set at %.1fpt instead of %.1fpt so its lines fit "
                            + "the column", size, style.basePt()),
                    longestLine(lines, style, size), ref);
        }
        if (!overflows && text.equals(raw)) {
            return;
        }
        target.empty();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (TableLayout.textWidth(line, style.face(), size) <= room) {
                target.appendText(line);
            } else {
                List<String> parts = split(line, style, size, room);
                target.appendText(parts.get(0));
                String indent = continuationIndent(line, style, size, room);
                for (String part : parts.subList(1, parts.size())) {
                    target.appendText("\n");
                    target.appendElement("span").addClass(CONTINUATION).text(indent + MARKER + " ");
                    target.appendText(part);
                }
                ctx.note(RenderContext.NoteType.VERBATIM_WRAPPED,
                        "Line is wider than the page even at the minimum size; carried over with a visible "
                                + "continuation mark (" + (parts.size() - 1) + " carry-over"
                                + (parts.size() > 2 ? "s" : "") + ")",
                        line.strip(), ref(pre, ctx));
            }
            if (i < lines.length - 1) {
                target.appendText("\n");
            }
        }
    }

    /** Width available to a line at {@code size}: the column less padding, borders and the safety margin. */
    private static double room(double availablePt, Style style, float size) {
        return availablePt - style.inset(size) - SAFETY_PT;
    }

    private static double widest(String[] lines, Style style, float size) {
        double w = 0;
        for (String line : lines) {
            w = Math.max(w, TableLayout.textWidth(line, style.face(), size));
        }
        return w;
    }

    private static String longestLine(String[] lines, Style style, float size) {
        String best = "";
        double w = -1;
        for (String line : lines) {
            double lw = TableLayout.textWidth(line, style.face(), size);
            if (lw > w) {
                w = lw;
                best = line;
            }
        }
        return best.strip();
    }

    /**
     * The hanging indent of a carried-over part: the line's own indentation (so
     * the continuation sits under its line, never at the left edge), unless that
     * would leave too little room, in which case the mark alone.
     */
    private static String continuationIndent(String line, Style style, float size, double room) {
        String indent = leadingWhitespace(line);
        double prefix = TableLayout.textWidth(indent + MARKER + " ", style.face(), size);
        return prefix > room * 0.5 ? "" : indent;
    }

    /**
     * Split an over-long line into parts that each fit — the first in the full
     * room, the rest behind the continuation prefix. Breaks after whitespace or
     * punctuation where that keeps most of the line, else at the last character
     * that fits. Every character of the line is kept, in order.
     */
    static List<String> split(String line, Style style, float size, double room) {
        String indent = continuationIndent(line, style, size, room);
        double contRoom = room - TableLayout.textWidth(indent + MARKER + " ", style.face(), size);
        List<String> parts = new ArrayList<>();
        String rest = line;
        boolean first = true;
        while (TableLayout.textWidth(rest, style.face(), size) > (first ? room : contRoom)) {
            int fit = fitChars(rest, style, size, first ? room : contRoom);
            int cut = fit;
            for (int k = fit; k > fit * 0.6; k--) {
                char c = rest.charAt(k - 1);
                if (Character.isWhitespace(c) || ",;:.)]}>".indexOf(c) >= 0) {
                    cut = k;
                    break;
                }
            }
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut);
            first = false;
        }
        parts.add(rest);
        return parts;
    }

    /** How many leading characters of {@code s} fit in {@code room} (at least one). */
    private static int fitChars(String s, Style style, float size, double room) {
        int n = 1;
        while (n < s.length() && TableLayout.textWidth(s.substring(0, n + 1), style.face(), size) <= room) {
            n++;
        }
        return n;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /** Tabs to spaces at {@link #TAB_WIDTH}-column stops, so what is measured is what is drawn. */
    static String expandTabs(String text) {
        if (text.indexOf('\t') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int col = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                int spaces = TAB_WIDTH - (col % TAB_WIDTH);
                out.append(" ".repeat(spaces));
                col += spaces;
            } else {
                out.append(c);
                col = c == '\n' ? 0 : col + 1;
            }
        }
        return out.toString();
    }

    private static String ref(Element pre, RenderContext ctx) {
        String ref = pre.attr(RenderContext.QA_ATTR);
        if (ref.isBlank()) {
            ref = ctx.newRef();
            pre.attr(RenderContext.QA_ATTR, ref);
        }
        return ref;
    }
}
