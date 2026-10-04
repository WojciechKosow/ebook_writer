package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Block;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Cell;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Fragment;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Line;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Page;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.PageKind;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Rect;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Row;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Table;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The global layout check: a pure, deterministic pass over the geometry the
 * renderer actually laid out ({@link LayoutSnapshot}). No text extraction, no
 * images, no judgement calls — every rule compares bounds against bounds:
 *
 * <ul>
 *   <li><b>Page bounds and footer.</b> A line of text or an image is atomic: the
 *       renderer moves it whole to the next page, so one that crosses the content
 *       area's foot was drawn into the footer/page-number band
 *       ({@link LayoutIssue.Type#FOOTER_OVERLAP}), and one that crosses a side
 *       margin left the content area ({@link LayoutIssue.Type#OUTSIDE_PAGE_BOUNDS}).
 *       Blocks — paragraphs, code, tables, callouts — may legitimately span pages;
 *       only their atoms are checked, so a continuation is never mistaken for an
 *       overflow.</li>
 *   <li><b>Clipping.</b> A line whose ink runs past the box it belongs to (a
 *       table cell, a code block) is drawn over its neighbour or cut off
 *       ({@link LayoutIssue.Type#TEXT_CLIPPING}).</li>
 *   <li><b>Tables.</b> Inside the column; cells inside the table, not
 *       overlapping, aligned to one column grid; no more cells than columns; every
 *       source row laid out once, in order; the header repeated when the table
 *       continues; the header never alone at a page foot.</li>
 *   <li><b>Continuations.</b> Every text fragment laid out exactly once (none
 *       lost, none duplicated), and no two lines drawn over each other.</li>
 *   <li><b>Orphan headings.</b> A heading (or component title) whose content
 *       begins on a later page.</li>
 *   <li><b>Empty pages.</b> A body page with nothing on it — cover, contents and
 *       chapter-opener pages are sparse by design and are recognised from the
 *       document, not from how full they look.</li>
 * </ul>
 *
 * Each issue names the element (its stable id), the page, the measured and the
 * allowed bounds, and the repair that can fix it upstream, if any.
 */
final class LayoutValidator {

    /** Geometry tolerance: sub-point rounding, glyph overhang, justified spacing. */
    static final float TOLERANCE_PT = 1.5f;

    private LayoutValidator() {
    }

    static LayoutValidationResult validate(LayoutSnapshot layout) {
        List<LayoutIssue> issues = new ArrayList<>();
        Map<String, Block> byId = new HashMap<>();
        for (Block b : layout.blocks()) {
            byId.putIfAbsent(b.id(), b); // a block split over pages is listed once per fragment; keep the first
        }
        checkLinesAndImages(layout, byId, issues);
        checkBlocksWithinColumn(layout, issues);
        checkTables(layout, issues);
        checkContinuations(layout, byId, issues);
        checkOrphanHeadings(layout, byId, issues);
        checkEmptyPages(layout, issues);
        return LayoutValidationResult.of(issues);
    }

    // ---- page bounds, footer, clipping ------------------------------------------------

    private static void checkLinesAndImages(LayoutSnapshot layout, Map<String, Block> byId, List<LayoutIssue> issues) {
        for (Line line : layout.lines()) {
            if (line.fragments().isEmpty()) {
                continue;
            }
            Block block = byId.get(line.blockId());
            if (block != null && block.decorative()) {
                continue; // the cover is composed to the paper's edges
            }
            Page page = layout.pageAt(line.rect().top());
            if (page == null) {
                issues.add(new LayoutIssue(LayoutIssue.Type.CONTENT_OVERFLOW, LayoutIssue.Severity.ERROR, 0,
                        line.blockId(), "a line of text lies on no page: \"" + text(line) + "\"",
                        line.ink().describe(), "within a page", LayoutIssue.Repair.NONE));
                continue;
            }
            Rect ink = line.ink();
            Rect area = page.content();
            String what = describe(block, line);
            if (ink.bottom() > area.bottom() + TOLERANCE_PT) {
                issues.add(new LayoutIssue(LayoutIssue.Type.FOOTER_OVERLAP, LayoutIssue.Severity.ERROR, page.number(),
                        line.blockId(), what + " runs into the footer/page-number area",
                        ink.describe(), "bottom ≤ " + fmt(area.bottom()) + " pt", LayoutIssue.Repair.BREAK_BEFORE));
            }
            if (ink.left() < area.left() - TOLERANCE_PT || ink.right() > area.right() + TOLERANCE_PT) {
                boolean inTable = block != null && block.tag().matches("td|th");
                issues.add(new LayoutIssue(inTable ? LayoutIssue.Type.TABLE_OVERFLOW : LayoutIssue.Type.OUTSIDE_PAGE_BOUNDS,
                        LayoutIssue.Severity.ERROR, page.number(), line.blockId(),
                        what + " extends past the text column", ink.describe(),
                        "x " + fmt(area.left()) + "–" + fmt(area.right()) + " pt", LayoutIssue.Repair.CONSTRAIN_WIDTH));
            } else if (block != null && ink.right() > block.rect().right() + TOLERANCE_PT) {
                issues.add(new LayoutIssue(LayoutIssue.Type.TEXT_CLIPPING, LayoutIssue.Severity.ERROR, page.number(),
                        line.blockId(), what + " overflows its box and is drawn over its neighbour or cut off",
                        ink.describe(), "x ≤ " + fmt(block.rect().right()) + " pt (" + block.describe() + ")",
                        LayoutIssue.Repair.CONSTRAIN_WIDTH));
            }
        }
        for (Block b : layout.blocks()) {
            if (!b.replaced() || b.decorative()) {
                continue;
            }
            Page page = layout.pageAt(b.rect().top());
            if (page == null) {
                continue;
            }
            if (b.rect().bottom() > page.content().bottom() + TOLERANCE_PT) {
                issues.add(new LayoutIssue(LayoutIssue.Type.CONTENT_OVERFLOW, LayoutIssue.Severity.ERROR, page.number(),
                        b.id(), b.describe() + " is taller than the space left on its page and runs past it",
                        b.rect().describe(), "bottom ≤ " + fmt(page.content().bottom()) + " pt",
                        b.rect().height() <= page.content().height() ? LayoutIssue.Repair.BREAK_BEFORE
                                : LayoutIssue.Repair.CONSTRAIN_HEIGHT));
            }
        }
    }

    /** Block boxes wider than the text column (a table, a code block, a figure). */
    private static void checkBlocksWithinColumn(LayoutSnapshot layout, List<LayoutIssue> issues) {
        Set<String> reported = new HashSet<>();
        for (Block b : layout.blocks()) {
            if (b.decorative() || b.tag().matches("html|body") || b.tag().matches("td|th|tr|thead|tbody|tfoot")) {
                continue;
            }
            Page page = layout.pageAt(b.rect().top());
            if (page == null || page.kind() == PageKind.COVER) {
                continue;
            }
            Rect area = page.content();
            if ((b.rect().right() > area.right() + TOLERANCE_PT || b.rect().left() < area.left() - TOLERANCE_PT)
                    && reported.add(b.id())) {
                issues.add(new LayoutIssue(b.tag().equals("table") ? LayoutIssue.Type.TABLE_OVERFLOW
                        : LayoutIssue.Type.OUTSIDE_PAGE_BOUNDS, LayoutIssue.Severity.ERROR, page.number(), b.id(),
                        b.describe() + " is wider than the text column", b.rect().describe(),
                        "x " + fmt(area.left()) + "–" + fmt(area.right()) + " pt", LayoutIssue.Repair.CONSTRAIN_WIDTH));
            }
        }
    }

    // ---- tables ---------------------------------------------------------------------

    private static void checkTables(LayoutSnapshot layout, List<LayoutIssue> issues) {
        for (Table t : layout.tables()) {
            Page first = layout.pageAt(t.rect().top());
            int page = first == null ? 0 : first.number();
            if (t.rows().size() != t.sourceRows()) {
                issues.add(table(t, page, LayoutIssue.Type.BROKEN_CONTINUATION, "laid out " + t.rows().size()
                        + " of its " + t.sourceRows() + " rows — rows were lost or duplicated", LayoutIssue.Repair.NONE));
            }
            Float[] colLeft = new Float[Math.max(t.columns(), 1)];
            Float[] colRight = new Float[Math.max(t.columns(), 1)];
            float lastTop = -Float.MAX_VALUE;
            for (Row row : t.rows()) {
                if (row.rect().top() < lastTop - TOLERANCE_PT) {
                    issues.add(table(t, page, LayoutIssue.Type.BROKEN_CONTINUATION,
                            "rows are out of order (a row is drawn above the one before it)", LayoutIssue.Repair.NONE));
                    break;
                }
                lastTop = row.rect().top();
                if (row.cells().size() > t.columns()) {
                    issues.add(table(t, page, LayoutIssue.Type.MALFORMED_TABLE, "a row has " + row.cells().size()
                            + " cells for " + t.columns() + " columns", LayoutIssue.Repair.NONE));
                    break;
                }
                Cell prev = null;
                for (Cell c : row.cells()) {
                    if (c.rect().left() < t.rect().left() - TOLERANCE_PT || c.rect().right() > t.rect().right() + TOLERANCE_PT) {
                        issues.add(table(t, page, LayoutIssue.Type.MALFORMED_TABLE, "a cell (\"" + clip(c.text())
                                + "\") lies outside the table: " + c.rect().describe(), LayoutIssue.Repair.CONSTRAIN_WIDTH));
                    }
                    if (prev != null && c.rect().left() < prev.rect().right() - TOLERANCE_PT) {
                        issues.add(table(t, page, LayoutIssue.Type.MALFORMED_TABLE, "cells overlap: \"" + clip(prev.text())
                                + "\" and \"" + clip(c.text()) + "\"", LayoutIssue.Repair.NONE));
                    }
                    if (c.column() < colLeft.length) {
                        if (colLeft[c.column()] == null) {
                            colLeft[c.column()] = c.rect().left();
                            colRight[c.column()] = c.rect().right();
                        } else if (Math.abs(colLeft[c.column()] - c.rect().left()) > TOLERANCE_PT
                                || Math.abs(colRight[c.column()] - c.rect().right()) > TOLERANCE_PT) {
                            issues.add(table(t, page, LayoutIssue.Type.MALFORMED_TABLE, "column " + (c.column() + 1)
                                    + " is not aligned from row to row", LayoutIssue.Repair.NONE));
                            colLeft[c.column()] = c.rect().left(); // report once per drift
                            colRight[c.column()] = c.rect().right();
                        }
                    }
                    prev = c;
                }
            }
            // Continuation: which pages hold body rows, and where the header sits.
            TreeSet<Integer> bodyPages = new TreeSet<>();
            Integer headerPage = null;
            for (Row row : t.rows()) {
                Page p = layout.pageAt(row.rect().top());
                if (p == null) {
                    continue;
                }
                if (row.header()) {
                    headerPage = headerPage == null ? p.number() : headerPage;
                } else {
                    bodyPages.add(p.number());
                }
            }
            if (headerPage != null && !bodyPages.isEmpty() && headerPage < bodyPages.first()) {
                issues.add(table(t, headerPage, LayoutIssue.Type.ORPHAN_HEADING,
                        "the table header is alone at the foot of page " + headerPage + "; its first row is on page "
                                + bodyPages.first(), LayoutIssue.Repair.BREAK_BEFORE));
            }
            if (headerPage != null && bodyPages.size() > 1 && !t.repeatsHeader()) {
                issues.add(table(t, bodyPages.first(), LayoutIssue.Type.MISSING_REPEATED_HEADER,
                        "the table continues on page " + bodyPages.higher(bodyPages.first())
                                + " without its header row", LayoutIssue.Repair.NONE));
            }
        }
    }

    private static LayoutIssue table(Table t, int page, LayoutIssue.Type type, String what, LayoutIssue.Repair repair) {
        return new LayoutIssue(type, LayoutIssue.Severity.ERROR, page, t.id(), "table#" + t.id() + ": " + what,
                t.rect().describe(), "within the text column", repair);
    }

    // ---- continuations ------------------------------------------------------------------

    /**
     * Every piece of text must be laid out exactly once: a fragment laid out twice
     * is duplicated content (a block restarted on the next page), a letter that
     * appears in no fragment is lost content, and two lines drawn over each other
     * are a continuation placed wrongly.
     */
    private static void checkContinuations(LayoutSnapshot layout, Map<String, Block> byId, List<LayoutIssue> issues) {
        Map<Integer, BitSet> covered = new HashMap<>();
        Map<String, Line> seen = new HashMap<>();
        Set<Integer> reportedNodes = new HashSet<>();
        for (Line line : layout.lines()) {
            for (Fragment f : line.fragments()) {
                if (f.node() < 0) {
                    continue; // generated by CSS, not source text
                }
                String key = f.node() + ":" + f.start() + ":" + f.end();
                Line earlier = seen.putIfAbsent(key, line);
                if (earlier != null && reportedNodes.add(f.node())) {
                    Page p = layout.pageAt(line.rect().top());
                    issues.add(new LayoutIssue(LayoutIssue.Type.BROKEN_CONTINUATION, LayoutIssue.Severity.ERROR,
                            p == null ? 0 : p.number(), line.blockId(), "\"" + clip(f.text()) + "\" is laid out twice",
                            line.rect().describe(), "once", LayoutIssue.Repair.NONE));
                }
                covered.computeIfAbsent(f.node(), k -> new BitSet()).set(f.start(), Math.max(f.start(), f.end()));
            }
        }
        for (Map.Entry<Integer, String> e : layout.texts().entrySet()) {
            String text = e.getValue();
            BitSet bits = covered.getOrDefault(e.getKey(), new BitSet());
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetterOrDigit(text.charAt(i)) && !bits.get(i)) {
                    issues.add(new LayoutIssue(LayoutIssue.Type.BROKEN_CONTINUATION, LayoutIssue.Severity.ERROR, 0,
                            "", "text was lost in layout: \"" + clip(text.substring(i)) + "\"", "missing", "laid out",
                            LayoutIssue.Repair.NONE));
                    break;
                }
            }
        }
        // Every source element with text of its own must have reached a line.
        Set<String> drawn = new HashSet<>();
        for (Line line : layout.lines()) {
            for (Fragment f : line.fragments()) {
                drawn.add(f.element() != null ? f.element() : line.blockId());
            }
        }
        for (Map.Entry<String, String> source : layout.textElements().entrySet()) {
            String id = source.getKey();
            if (!drawn.contains(id)) {
                issues.add(new LayoutIssue(LayoutIssue.Type.BROKEN_CONTINUATION, LayoutIssue.Severity.ERROR, 0, id,
                        source.getValue() + "#" + id + " holds text that was never laid out — "
                                + "the content is missing from the PDF", "not drawn", "laid out on a page",
                        LayoutIssue.Repair.NONE));
            }
        }
        // Lines drawn over each other on the same page.
        Map<Integer, List<Line>> byPage = new HashMap<>();
        for (Line line : layout.lines()) {
            Page p = layout.pageAt(line.rect().top());
            Block b = byId.get(line.blockId());
            if (p != null && !line.fragments().isEmpty() && (b == null || !b.decorative())) {
                byPage.computeIfAbsent(p.number(), k -> new ArrayList<>()).add(line);
            }
        }
        for (Map.Entry<Integer, List<Line>> e : byPage.entrySet()) {
            List<Line> lines = e.getValue();
            outer:
            for (int i = 0; i < lines.size(); i++) {
                for (int j = i + 1; j < lines.size(); j++) {
                    if (overlap(lines.get(i).rect(), lines.get(j).rect())) {
                        issues.add(new LayoutIssue(LayoutIssue.Type.BROKEN_CONTINUATION, LayoutIssue.Severity.ERROR,
                                e.getKey(), lines.get(j).blockId(), "\"" + text(lines.get(j)) + "\" is drawn over \""
                                + text(lines.get(i)) + "\"", lines.get(j).rect().describe(),
                                "clear of " + lines.get(i).rect().describe(), LayoutIssue.Repair.NONE));
                        break outer; // one per page is enough to fail it
                    }
                }
            }
        }
    }

    /** Two line boxes overlap if they share more than the tolerance in both directions. */
    private static boolean overlap(Rect a, Rect b) {
        float x = Math.min(a.right(), b.right()) - Math.max(a.left(), b.left());
        float y = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        return x > TOLERANCE_PT && y > TOLERANCE_PT;
    }

    // ---- orphan headings ----------------------------------------------------------------

    /**
     * A heading, or a component's label/title, whose content begins on a later
     * page: the next line of text after it (in reading order, in the same
     * chapter) is on a later page than its own last line.
     */
    private static void checkOrphanHeadings(LayoutSnapshot layout, Map<String, Block> byId, List<LayoutIssue> issues) {
        List<Line> lines = layout.lines().stream().filter(l -> !l.fragments().isEmpty()).toList();
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < lines.size() - 1; i++) {
            Line line = lines.get(i);
            Block b = byId.get(line.blockId());
            if (b == null || !isTitle(b) || !reported.add(b.id() + "@" + i)) {
                continue;
            }
            Line next = lines.get(i + 1);
            if (next.blockId() != null && next.blockId().equals(line.blockId())) {
                continue; // the title wraps; judge its last line
            }
            Block nb = byId.get(next.blockId());
            if (nb != null && isTitle(nb)) {
                continue; // a title run (h2 → h3, label → title): judge the last of them
            }
            if (nb != null && !nb.chapter().equals(b.chapter())) {
                continue; // nothing follows it in its chapter
            }
            Page here = layout.pageAt(line.rect().top());
            Page there = layout.pageAt(next.rect().top());
            if (here != null && there != null && there.number() > here.number()) {
                issues.add(new LayoutIssue(LayoutIssue.Type.ORPHAN_HEADING, LayoutIssue.Severity.ERROR, here.number(),
                        b.id(), b.describe() + " ends page " + here.number() + "; its content begins on page "
                        + there.number(), line.rect().describe(), "on the same page as its content",
                        LayoutIssue.Repair.BREAK_BEFORE));
            }
        }
    }

    private static boolean isTitle(Block b) {
        return b.tag().matches("h[2-4]") || b.hasClass("cmp-label") || b.hasClass("cmp-title")
                || b.hasClass("steps-eyebrow") || b.hasClass("flow-title");
    }

    // ---- empty pages -------------------------------------------------------------------

    /**
     * A body page with nothing on it. Cover, contents and chapter-opener pages are
     * recognised from the document (their elements), never from how full they
     * look, so a deliberately sparse page is never flagged.
     */
    private static void checkEmptyPages(LayoutSnapshot layout, List<LayoutIssue> issues) {
        Map<Integer, Integer> content = new HashMap<>();
        for (Line line : layout.lines()) {
            Page p = layout.pageAt(line.rect().top());
            if (p != null && !line.fragments().isEmpty()) {
                content.merge(p.number(), 1, Integer::sum);
            }
        }
        for (Block b : layout.blocks()) {
            Page p = layout.pageAt(b.rect().top());
            if (p != null && b.replaced()) {
                content.merge(p.number(), 1, Integer::sum);
            }
        }
        for (Table t : layout.tables()) {
            for (Row row : t.rows()) {
                Page p = layout.pageAt(row.rect().top());
                if (p != null) {
                    content.merge(p.number(), 1, Integer::sum);
                }
            }
        }
        // Pages whose only text is a chapter's opener band, while the chapter goes
        // on overleaf: the opener was left alone on its page.
        Map<String, Block> byId = new HashMap<>();
        for (Block b : layout.blocks()) {
            byId.putIfAbsent(b.id(), b);
        }
        Map<Integer, Boolean> openerOnly = new HashMap<>();
        Map<Integer, String> chapterOf = new HashMap<>();
        for (Line line : layout.lines()) {
            Page p = layout.pageAt(line.rect().top());
            Block b = byId.get(line.blockId());
            if (p == null || line.fragments().isEmpty() || b == null) {
                continue;
            }
            boolean opener = b.hasClass("chapter-num") || b.hasClass("chapter-title") || b.hasClass("chapter-intro")
                    || b.hasClass("chapter-meta");
            openerOnly.merge(p.number(), opener, Boolean::logicalAnd);
            chapterOf.putIfAbsent(p.number(), b.chapter());
        }
        for (Page p : layout.pages()) {
            if (p.kind() != PageKind.BODY) {
                continue;
            }
            if (content.getOrDefault(p.number(), 0) == 0) {
                issues.add(new LayoutIssue(LayoutIssue.Type.SUSPICIOUS_EMPTY_PAGE, LayoutIssue.Severity.WARNING,
                        p.number(), "", "page " + p.number() + " is a body page with nothing on it",
                        "empty", "content, or an opener/cover/contents page", LayoutIssue.Repair.NONE));
            } else if (openerOnly.getOrDefault(p.number(), false)
                    && chapterOf.getOrDefault(p.number(), "").equals(chapterOf.get(p.number() + 1))) {
                issues.add(new LayoutIssue(LayoutIssue.Type.SUSPICIOUS_EMPTY_PAGE, LayoutIssue.Severity.WARNING,
                        p.number(), "", "page " + p.number() + " holds only its chapter's opener; the chapter starts "
                        + "on page " + (p.number() + 1), "opener only", "the opener with the start of its chapter",
                        LayoutIssue.Repair.NONE));
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String describe(Block block, Line line) {
        return (block == null ? "a line" : block.describe().replaceAll(" \".*", "")) + " (\"" + text(line) + "\")";
    }

    private static String text(Line line) {
        StringBuilder sb = new StringBuilder();
        for (Fragment f : line.fragments()) {
            sb.append(f.text());
        }
        return clip(sb.toString().strip());
    }

    private static String clip(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }
}
