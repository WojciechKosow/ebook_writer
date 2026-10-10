package com.ebookwriter.SaaS.service.ebook;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * How much page a chapter takes, measured from its typeset blocks rather than
 * counted in words. Words alone misjudge any book that is not plain prose: code,
 * verse, tables, lists and boxes fill far more page per word than a paragraph.
 * The chapter is rendered with the book's own renderer and each block's height
 * is estimated with the bundled fonts ({@link BlockMetrics}), so the measure
 * follows what the PDF will hold.
 */
final class LengthMeter {

    /** An image's height is unknown before layout: count it as a third of a page. */
    static final double FIGURE_PAGES = 0.35;

    private static final EbookContentRenderer RENDERER = new EbookContentRenderer();

    private LengthMeter() {
    }

    /** Estimated content pages of a chapter body (without its opener). */
    static double pages(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return 0;
        }
        Document doc = Jsoup.parseBodyFragment(RENDERER.renderBody(markdown));
        Element body = doc.body();
        float column = PageGeometry.book().contentWidthPt();
        RenderContext scratch = new RenderContext();
        for (Element pre : body.select("pre")) {
            VerbatimLayout.apply(pre, EbookContentRenderer.insetWidth(pre, column), scratch);
        }
        double page = PageGeometry.book().contentHeightPt();
        double height = 0;
        double figures = 0;
        for (Element block : body.children()) {
            double h = BlockMetrics.height(block, column);
            if (h >= 0) {
                height += h;
            } else if (!block.select("img").isEmpty()) {
                figures += FIGURE_PAGES;
            } else {
                height += BlockMetrics.height(new Element("p").text(block.text()), column);
            }
        }
        return height / page + figures;
    }

    /**
     * The chapter's length in "page words": its measured pages times the words a
     * page of prose holds. Comparable with the plan's word budgets, which are
     * pages times {@link ChapterGenerationService#WORDS_PER_PAGE}.
     */
    static int pageWords(String markdown) {
        return (int) Math.round(pages(markdown) * ChapterGenerationService.WORDS_PER_PAGE);
    }
}
