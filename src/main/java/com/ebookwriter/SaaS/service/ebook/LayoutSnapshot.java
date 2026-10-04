package com.ebookwriter.SaaS.service.ebook;

import com.openhtmltopdf.layout.LayoutContext;
import com.openhtmltopdf.newtable.TableBox;
import com.openhtmltopdf.newtable.TableCellBox;
import com.openhtmltopdf.newtable.TableRowBox;
import com.openhtmltopdf.newtable.TableSectionBox;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.InlineLayoutBox;
import com.openhtmltopdf.render.InlineText;
import com.openhtmltopdf.render.LineBox;
import com.openhtmltopdf.render.PageBox;
import org.w3c.dom.Element;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The laid-out book as geometry: what the renderer actually placed, read from
 * its own box tree after layout and before the PDF is written. Every block,
 * line, text fragment, table row and cell with its bounds, and every page with
 * the rectangle content may occupy (from the {@code @page} rule: the page less
 * its margins, where the running footer/page number lives).
 *
 * <p>All lengths are in points, in document coordinates (pages stacked
 * top-to-bottom). The snapshot is plain data, so {@link LayoutValidator} is
 * pure and can be tested on hand-built snapshots as well as real renders.
 */
record LayoutSnapshot(List<Page> pages, List<Block> blocks, List<Line> lines, List<Table> tables,
                      Map<Integer, String> texts, Map<String, String> textElements) {

    /** The attribute that gives every element in the render a stable id (see {@link LayoutRepair}). */
    static final String ID_ATTR = "data-lv";

    enum PageKind { COVER, FRONT_MATTER, OPENER, BODY }

    record Rect(float left, float top, float right, float bottom) {
        float height() {
            return bottom - top;
        }

        float width() {
            return right - left;
        }

        String describe() {
            return String.format("x %.1f–%.1f, y %.1f–%.1f pt", left, right, top, bottom);
        }
    }

    /**
     * A page: its number (1-based), its content area (where content may go;
     * below it are the footer and page number), and what kind of page it is —
     * cover, front matter and chapter-opener pages are sparse by design.
     */
    record Page(int number, Rect content, float pageTop, float pageBottom, PageKind kind) {
        /** Where a point lies relative to this page's content area, for diagnostics. */
        String local(float y) {
            return String.format("%.1fpt from the page's content top", y - content.top());
        }
    }

    /**
     * A laid-out block element. {@code id} is its stable element id;
     * {@code chapter} the chapter anchor it belongs to; {@code decorative}
     * whether it is part of the full-bleed cover, which is composed to the
     * paper's edges by design.
     */
    record Block(String id, String tag, String classes, String text, Rect rect, String chapter, boolean replaced,
                 boolean decorative) {
        boolean hasClass(String c) {
            return (" " + classes + " ").contains(" " + c + " ");
        }

        String describe() {
            String t = text.length() > 48 ? text.substring(0, 48) + "…" : text;
            return tag + (classes.isBlank() ? "" : "." + classes.replace(' ', '.')) + "#" + id
                    + (t.isBlank() ? "" : " \"" + t + "\"");
        }
    }

    /**
     * A text fragment on a line: which text node ({@code node}, -1 for
     * CSS-generated text) and which part of it, and the element it belongs to.
     */
    record Fragment(int node, int start, int end, String text, Rect rect, String element) {
    }

    /** A line of text, with the block it belongs to and the extent of its ink. */
    record Line(String blockId, Rect rect, Rect ink, List<Fragment> fragments) {
    }

    record Cell(int column, Rect rect, String text) {
    }

    record Row(boolean header, Rect rect, List<Cell> cells) {
    }

    /**
     * A laid-out table: its declared column count (the colgroup Step 2 writes, or
     * the widest row), whether the renderer repeats its header on continuation
     * pages, and its rows in order.
     */
    record Table(String id, Rect rect, int columns, int sourceRows, boolean repeatsHeader, List<Row> rows) {
    }

    // ---- Capture -------------------------------------------------------------------

    /** Read the laid-out box tree of a renderer whose {@code layout()} has run. */
    static LayoutSnapshot capture(PdfBoxRenderer renderer) {
        float dpp = renderer.getDotsPerPoint();
        LayoutContext ctx = renderer.getSharedContext().newLayoutContextInstance();
        Capture c = new Capture(dpp);
        BlockBox root = renderer.getRootBox();
        for (PageBox p : root.getLayer().getPages()) {
            // The content area: the page's own width less its margins, over the
            // stretch of the document the page holds.
            Rectangle r = p.getDocumentCoordinatesContentBounds(ctx);
            c.pages.add(new float[]{0, p.getTop() / dpp, r.width / dpp, p.getBottom() / dpp,
                    p.getTop() / dpp, p.getBottom() / dpp});
        }
        c.walk(root, null, "", false);
        c.walkLayers(root.getLayer());
        c.sourceText(root.getElement());
        return c.build();
    }

    private static final class Capture {
        final float dpp;
        final List<float[]> pages = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        final List<Table> tables = new ArrayList<>();
        final Map<Object, Integer> nodeIdentity = new IdentityHashMap<>();
        final Map<Integer, String> texts = new java.util.HashMap<>();

        Capture(float dpp) {
            this.dpp = dpp;
        }

        Rect rect(Box b) {
            return new Rect(b.getAbsX() / dpp, b.getAbsY() / dpp,
                    (b.getAbsX() + b.getWidth()) / dpp, (b.getAbsY() + b.getHeight()) / dpp);
        }

        final java.util.Set<Box> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String, String> textElements = new java.util.LinkedHashMap<>();

        /**
         * Positioned and floated boxes live in their own layers, outside the
         * normal-flow children: walk them too, so nothing laid out is missed.
         */
        void walkLayers(com.openhtmltopdf.layout.Layer layer) {
            if (layer == null) {
                return;
            }
            for (BlockBox f : layer.getFloats()) {
                walk(f, idOf(f), chapterOf(f), onCover(f));
            }
            for (com.openhtmltopdf.layout.Layer child : layer.getChildren()) {
                Box master = child.getMaster();
                if (master != null) {
                    walk(master, idOf(master), chapterOf(master), onCover(master));
                }
                walkLayers(child);
            }
        }

        /** The nearest element (the box's own, else a box ancestor's) — positioned boxes may have no box parent. */
        private Element elementOf(Box b) {
            for (Box cur = b; cur != null; cur = cur.getParent()) {
                if (cur.getElement() != null) {
                    return cur.getElement();
                }
            }
            return null;
        }

        String idOf(Box b) {
            for (org.w3c.dom.Node n = elementOf(b); n instanceof Element e; n = e.getParentNode()) {
                if (e.hasAttribute(ID_ATTR)) {
                    return e.getAttribute(ID_ATTR);
                }
            }
            return null;
        }

        /** Whether the box belongs to the cover (by its source element's ancestry). */
        boolean onCover(Box b) {
            for (org.w3c.dom.Node n = elementOf(b); n instanceof Element e; n = e.getParentNode()) {
                if ((" " + e.getAttribute("class") + " ").contains(" cover ")) {
                    return true;
                }
            }
            return false;
        }

        String chapterOf(Box b) {
            for (org.w3c.dom.Node n = elementOf(b); n instanceof Element e; n = e.getParentNode()) {
                if (e.getAttribute("id").startsWith("chapter-")) {
                    return e.getAttribute("id");
                }
            }
            return "";
        }

        /** Every source element that holds text of its own (id → description): each must reach the page. */
        void sourceText(Element root) {
            if (root == null) {
                return;
            }
            org.w3c.dom.Node body = root.getOwnerDocument().getElementsByTagName("body").item(0);
            collectText(body == null ? root : body);
        }

        private void collectText(org.w3c.dom.Node node) {
            for (org.w3c.dom.Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() == org.w3c.dom.Node.TEXT_NODE && node instanceof Element e
                        && c.getNodeValue() != null && c.getNodeValue().codePoints().anyMatch(Character::isLetterOrDigit)
                        && e.hasAttribute(ID_ATTR)) {
                    String text = e.getTextContent().strip().replaceAll("\\s+", " ");
                    textElements.putIfAbsent(e.getAttribute(ID_ATTR), e.getTagName()
                            + (e.getAttribute("class").isBlank() ? "" : "." + e.getAttribute("class").replace(' ', '.'))
                            + " \"" + (text.length() > 48 ? text.substring(0, 48) + "…" : text) + "\"");
                } else if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    collectText(c);
                }
            }
        }

        void walk(Box box, String blockId, String chapter, boolean decorative) {
            if (!visited.add(box)) {
                return;
            }
            Element e = box.getElement();
            String id = blockId;
            String ch = chapter;
            boolean cover = decorative;
            if (e != null && box instanceof BlockBox bb && !(box instanceof LineBox)) {
                String anchor = e.getAttribute("id");
                if (anchor.startsWith("chapter-")) {
                    ch = anchor;
                }
                id = e.getAttribute(ID_ATTR);
                cover = cover || (" " + e.getAttribute("class") + " ").contains(" cover ");
                blocks.add(new Block(id, e.getTagName(), e.getAttribute("class"),
                        e.getTextContent() == null ? "" : e.getTextContent().strip().replaceAll("\\s+", " "),
                        rect(box), ch, bb.isReplaced(), cover));
                if (box instanceof TableBox table) {
                    tables.add(table(table, id));
                }
            }
            if (box instanceof LineBox line) {
                line(line, id);
                return;
            }
            for (Box child : box.getChildren()) {
                walk(child, id, ch, cover);
            }
        }

        void line(LineBox line, String blockId) {
            List<Fragment> fragments = new ArrayList<>();
            float[] ink = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (Box child : line.getChildren()) {
                inline(child, fragments, ink);
            }
            Rect r = rect(line);
            Rect inkRect = ink[0] == Float.MAX_VALUE ? r : new Rect(ink[0], ink[1], ink[2], ink[3]);
            lines.add(new Line(blockId, r, inkRect, fragments));
        }

        void inline(Box box, List<Fragment> out, float[] ink) {
            if (box instanceof InlineLayoutBox ilb) {
                for (Object o : ilb.getInlineChildren()) {
                    if (o instanceof InlineText t) {
                        String s = t.getSubstring();
                        float left = (ilb.getAbsX() + t.getX()) / dpp;
                        float right = left + t.getWidth() / dpp;
                        float top = ilb.getAbsY() / dpp;
                        float bottom = (ilb.getAbsY() + ilb.getHeight()) / dpp;
                        if (s != null && !s.isBlank()) {
                            // Text generated by CSS (::before/::after, the contents page's
                            // target-counter page numbers) is not source text: no node.
                            int node = -1;
                            if (ilb.getPseudoElementOrClass() == null && !t.isDynamicFunction()) {
                                node = nodeIdentity.computeIfAbsent(t.getMasterText(), k -> nodeIdentity.size());
                                texts.putIfAbsent(node, t.getMasterText());
                            }
                            Element owner = ilb.getElement();
                            String element = owner != null && owner.hasAttribute(ID_ATTR) ? owner.getAttribute(ID_ATTR) : null;
                            out.add(new Fragment(node, t.getStart(), t.getEnd(), s, new Rect(left, top, right, bottom), element));
                            ink[0] = Math.min(ink[0], left);
                            ink[1] = Math.min(ink[1], top);
                            ink[2] = Math.max(ink[2], right);
                            ink[3] = Math.max(ink[3], bottom);
                        }
                    } else if (o instanceof Box b) {
                        inline(b, out, ink);
                    }
                }
            } else if (box != null) {
                // An inline-block or replaced element on the line.
                Rect r = rect(box);
                ink[0] = Math.min(ink[0], r.left());
                ink[1] = Math.min(ink[1], r.top());
                ink[2] = Math.max(ink[2], r.right());
                ink[3] = Math.max(ink[3], r.bottom());
                // Its own content (lines, nested blocks) is walked like any block.
                walk(box, idOf(box), chapterOf(box), onCover(box));
            }
        }

        Table table(TableBox table, String id) {
            List<Row> rows = new ArrayList<>();
            int widest = 0;
            for (Box section : table.getChildren()) {
                if (!(section instanceof TableSectionBox)) {
                    continue;
                }
                boolean header = section.getElement() != null && "thead".equals(section.getElement().getTagName());
                for (Box rowBox : section.getChildren()) {
                    if (!(rowBox instanceof TableRowBox)) {
                        continue;
                    }
                    List<Cell> cells = new ArrayList<>();
                    int col = 0;
                    for (Box cellBox : rowBox.getChildren()) {
                        if (cellBox instanceof TableCellBox) {
                            String text = cellBox.getElement() == null ? "" : cellBox.getElement().getTextContent();
                            cells.add(new Cell(col++, rect(cellBox), text == null ? "" : text.strip()));
                        }
                    }
                    widest = Math.max(widest, cells.size());
                    rows.add(new Row(header, rect(rowBox), cells));
                }
            }
            Element e = table.getElement();
            int declared = e.getElementsByTagName("col").getLength();
            int sourceRows = e.getElementsByTagName("tr").getLength();
            return new Table(id, rect(table), declared > 0 ? declared : widest, sourceRows,
                    table.getStyle().isPaginateTable(), rows);
        }

        LayoutSnapshot build() {
            List<Page> out = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                float[] p = pages.get(i);
                Rect content = new Rect(p[0], p[1], p[2], p[3]);
                out.add(new Page(i + 1, content, p[4], p[5], kindOf(content)));
            }
            return new LayoutSnapshot(out, blocks, lines, tables, texts, textElements);
        }

        /** A page's kind, from the blocks that begin on it (cover, contents, opener). */
        PageKind kindOf(Rect content) {
            for (Block b : blocks) {
                if (b.rect().top() >= content.top() - 0.01f && b.rect().top() < content.bottom()) {
                    if (b.hasClass("cover")) {
                        return PageKind.COVER;
                    }
                    if (b.hasClass("toc")) {
                        return PageKind.FRONT_MATTER;
                    }
                    if (b.hasClass("opener")) {
                        return PageKind.OPENER;
                    }
                }
            }
            return PageKind.BODY;
        }
    }

    /** The page a document y lies on (by content area), or null. */
    Page pageAt(float y) {
        for (Page p : pages) {
            if (y >= p.pageTop() - 0.01f && y < p.pageBottom()) {
                return p;
            }
        }
        return null;
    }
}
