package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ebook.TableLayout.Typeface;
import org.jsoup.nodes.Element;

/**
 * Estimated heights of the book's blocks, for keeping logical blocks together:
 * how tall a block is, and how much of it must share a page with its title —
 * its <b>start</b>. Sizes, line heights, paddings and margins mirror
 * {@code pdf/ebook.css}; text is measured with the bundled fonts
 * ({@link TableLayout#textLines}). Estimates, not layout: the guard built from
 * them ({@link EbookContentRenderer}) adds headroom.
 *
 * <p>A start is what a reader needs to see under a title for the page break to
 * read as intentional:
 * <ul>
 *   <li>a paragraph, list or quote — its first {@link #START_LINES} lines (the
 *       rest may continue overleaf);</li>
 *   <li>a code block, figure-free component or short table — all of it if it fits
 *       on a page, since the stylesheet moves those whole anyway; otherwise
 *       its first lines (a code block or component taller than a page splits);</li>
 *   <li>an exercise — its label, title and the first lines of its body (an
 *       exercise may run on over pages);</li>
 *   <li>a longer table — its header and first row ({@link TableLayout}).</li>
 * </ul>
 * A block whose height can't be known here (a figure: its image's shape isn't in
 * the HTML) reports {@code -1}.
 */
final class BlockMetrics {

    /** Lines of a splittable block that must stay with its title (≥ the stylesheet's orphans: 2). */
    static final int START_LINES = 3;

    private static final double BODY_PT = 11;
    private static final double BODY_LINE = BODY_PT * 1.52;      // body { line-height: 1.52 }
    private static final double P_MARGIN = 0.65 * BODY_PT;       // p { margin: 0 0 0.65em }
    private static final double LIST_INDENT = 1.45 * BODY_PT;    // ul { margin-left: 1.3em } + li padding
    private static final double LIST_MARGIN = 0.7 * BODY_PT;
    private static final double CODE_PT = 9;
    private static final double PRE_CHROME = 2 * 0.8 * CODE_PT + 1.5 + 0.9 * CODE_PT; // padding, border, margin
    private static final double CMP_MARGIN = 1.15 * BODY_PT;     // .cmp { margin: 1.15em 0 }
    private static final double CMP_PAD = 2 * 0.85 * BODY_PT;    // the roomiest component padding
    private static final double CMP_INSET = 2 * 1.1 * BODY_PT + 3;
    private static final double LABEL = 8.5 * 1.52 + 0.45 * 8.5; // .cmp-label
    private static final double TITLE = 12 * 1.52 + 0.35 * 12;   // .cmp-title

    private BlockMetrics() {
    }

    /** Height (pt) of a block set in a column {@code width} wide, or -1 if unknown. */
    static double height(Element block, double width) {
        return measure(block, width, false);
    }

    /** Height (pt) of the part of a block that must stay with its title, or -1 if unknown. */
    static double start(Element block, double width) {
        return measure(block, width, true);
    }

    private static double measure(Element e, double width, boolean start) {
        String tag = e.tagName();
        double page = PageGeometry.book().contentHeightPt();
        switch (tag) {
            case "h2":
                return lines(e.text(), Typeface.SANS_BOLD, 15.5, width) * 15.5 * 1.22 + (1.5 + 0.5 + 0.25) * 15.5 + 1;
            case "h3":
                return lines(e.text(), Typeface.SANS_BOLD, 12.5, width) * 12.5 * 1.22 + (1.25 + 0.35) * 12.5;
            case "h4":
                return lines(e.text(), Typeface.SANS_BOLD, 9.5, width) * 9.5 * 1.22 + (1.1 + 0.3) * 9.5;
            case "p":
            case "blockquote": {
                int lines = lines(e.text(), Typeface.SERIF, BODY_PT, tag.equals("p") ? width : width - BODY_PT);
                return (start ? Math.min(lines, START_LINES) : lines) * BODY_LINE + P_MARGIN;
            }
            case "ul":
            case "ol": {
                double total = LIST_MARGIN;
                for (Element li : e.children()) {
                    int lines = lines(li.text(), Typeface.SERIF, BODY_PT, width - LIST_INDENT);
                    if (start) {
                        return total + Math.min(lines, START_LINES) * BODY_LINE;
                    }
                    total += lines * BODY_LINE + 0.32 * BODY_PT;
                }
                return total;
            }
            case "pre": {
                // Verbatim lines never wrap (VerbatimLayout fitted them): one
                // source line is one printed line, at the block's own size.
                VerbatimLayout.Style style = VerbatimLayout.style(e);
                float size = VerbatimLayout.fontSize(e);
                boolean text = style == VerbatimLayout.TEXT;
                double lineHeight = size * (text ? 1.5 : 1.42);
                double chrome = text ? 0.9 * CODE_PT : PRE_CHROME * size / CODE_PT;
                int lines = e.wholeText().strip().split("\n", -1).length;
                double whole = lines * lineHeight + chrome;
                // Moved whole when it fits a page (page-break-inside: avoid); split beyond.
                return start && whole > page ? (START_LINES + 1) * lineHeight + chrome : whole;
            }
            case "table": {
                double s = TableLayout.startHeight(e);
                return start && !e.hasClass(TableLayout.WHOLE) && s > 0 ? s : TableLayout.wholeHeight(e);
            }
            case "div":
                if (e.hasClass("cmp")) {
                    return component(e, width, start, page);
                }
                return -1;
            default:
                return -1; // figures and anything else: size unknown here
        }
    }

    /**
     * A component: its label and title, then its body. A component that fits a
     * page is moved whole (page-break-inside: avoid), so its start is all of it;
     * an exercise may split, and anything taller than a page splits, so their
     * start is the header and the first lines of the body.
     */
    private static double component(Element cmp, double width, boolean start, double page) {
        double header = CMP_MARGIN + CMP_PAD / 2;
        header += cmp.selectFirst("> .cmp-label") != null || cmp.selectFirst("> .steps-eyebrow") != null ? LABEL : 0;
        header += cmp.selectFirst("> .cmp-title") != null || cmp.selectFirst("> .flow-title") != null ? TITLE : 0;
        double inner = width - CMP_INSET;
        double body = 0;
        double firstBlock = -1;
        Element content = cmp.selectFirst("> .cmp-body");
        for (Element block : (content != null ? content : cmp).children()) {
            if (block.hasClass("cmp-label") || block.hasClass("cmp-title")) {
                continue;
            }
            double h = measure(block, inner, false);
            if (h < 0) {
                // steps, flows, checklists: estimate from their text
                h = lines(block.text(), Typeface.SERIF, BODY_PT, inner) * BODY_LINE;
            }
            if (firstBlock < 0) {
                firstBlock = measure(block, inner, true) >= 0 ? measure(block, inner, true) : Math.min(h, START_LINES * BODY_LINE);
            }
            body += h;
        }
        double whole = header + body + CMP_PAD / 2;
        boolean splits = cmp.hasClass("cmp--exercise") || whole > page;
        return start && splits ? header + Math.max(firstBlock, 0) : whole;
    }

    private static int lines(String text, Typeface face, double size, double width) {
        return TableLayout.textLines(text, face, (float) size, Math.max(width, 20));
    }
}
