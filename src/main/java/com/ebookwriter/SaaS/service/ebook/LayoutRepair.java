package com.ebookwriter.SaaS.service.ebook;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Locale;

/**
 * Upstream repairs for the layout check: each fixes the <b>source</b> of a
 * layout problem in the book HTML — a page break before a block, a width or
 * height constraint — so the renderer lays the book out again correctly. The
 * rendered PDF is never patched.
 *
 * <p>Elements are named by a stable id ({@link LayoutSnapshot#ID_ATTR}),
 * assigned in document order before the first layout. Repairs only add styles,
 * so an id names the same element in every repaired re-render.
 */
final class LayoutRepair {

    private LayoutRepair() {
    }

    /**
     * Number every element in the body, in document order. Idempotent: a
     * document already numbered keeps its ids.
     */
    static void assignIds(Document doc) {
        int n = 0;
        for (Element e : doc.body().getAllElements()) {
            if (e != doc.body() && !e.hasAttr(LayoutSnapshot.ID_ATTR)) {
                e.attr(LayoutSnapshot.ID_ATTR, "e" + n);
            }
            n++;
        }
    }

    /**
     * Apply the issue's repair to the document. Returns a short account of what
     * was done, or null when the repair does not apply (no such element, or the
     * fix would be wrong here — e.g. a page break before a chapter's first block,
     * which would only leave the chapter opener alone on its page).
     */
    static String apply(Document doc, LayoutIssue issue) {
        Element e = doc.selectFirst("[" + LayoutSnapshot.ID_ATTR + "=" + issue.element() + "]");
        if (e == null) {
            return null;
        }
        return switch (issue.repair()) {
            case BREAK_BEFORE -> breakBefore(e);
            case CONSTRAIN_WIDTH -> constrainWidth(e);
            case CONSTRAIN_HEIGHT -> constrainHeight(e);
            case NONE -> null;
        };
    }

    /**
     * Start the element's logical block on a new page: the keep-together group it
     * belongs to (Step 4), the table's start wrapper, the component it titles, the
     * table row it sits in — or the element itself.
     */
    private static String breakBefore(Element e) {
        Element target = e.closest(".keep-with-next");
        if (target == null) {
            target = e.closest(".table-start");
        }
        if (target == null && (e.hasClass("cmp-label") || e.hasClass("cmp-title"))) {
            target = e.closest(".cmp");
        }
        if (target == null) {
            Element row = e.closest("tr");
            target = row != null ? row : blockOf(e);
        }
        Element chapterBody = target.closest(".chapter-body");
        if (chapterBody != null && chapterBody.firstElementChild() == target) {
            return null; // the chapter's first block belongs under its opener
        }
        addStyle(target, "page-break-before: always");
        return "page break before " + target.tagName() + "#" + target.attr(LayoutSnapshot.ID_ATTR);
    }

    /** Let the element (a table: its cells) wrap anywhere and stay within the column. */
    private static String constrainWidth(Element e) {
        Element target = e.closest("table");
        if (target == null) {
            target = blockOf(e);
        }
        addStyle(target, "max-width: 100%; overflow-wrap: anywhere; word-wrap: break-word; word-break: break-all");
        if (target.tagName().equals("table")) {
            addStyle(target, "width: 100%; table-layout: fixed");
        }
        return "width constrained on " + target.tagName() + "#" + target.attr(LayoutSnapshot.ID_ATTR);
    }

    /** Cap an image to the page's content height (keeping its proportions). */
    private static String constrainHeight(Element e) {
        float max = 0.9f * PageGeometry.book().contentHeightPt();
        addStyle(e, String.format(Locale.ROOT, "max-height: %.0fpt; width: auto", max));
        return "height capped on " + e.tagName() + "#" + e.attr(LayoutSnapshot.ID_ATTR);
    }

    /** The element itself if it is a block, else its nearest block ancestor. */
    private static Element blockOf(Element e) {
        Element cur = e;
        while (cur != null && cur.tagName().matches("span|a|strong|em|b|i|code")) {
            cur = cur.parent();
        }
        return cur == null ? e : cur;
    }

    private static void addStyle(Element e, String css) {
        String style = e.attr("style").strip();
        e.attr("style", style.isEmpty() ? css : (style.endsWith(";") ? style + " " : style + "; ") + css);
    }
}
