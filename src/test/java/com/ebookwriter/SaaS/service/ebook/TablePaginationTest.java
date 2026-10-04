package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How tables meet page breaks, checked on rendered PDFs: tables split between
 * whole rows, the header repeats on every continuation page, a header is never
 * left at a page foot without its first row, and short tables stay whole — for
 * tables starting at every height on the page, with and without a heading.
 */
class TablePaginationTest {

    private final PdfGenerationService service =
            new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);

    @Test
    void longTablesSplitBetweenWholeRowsUnderARepeatedHeader() throws Exception {
        int[] longRows = new int[12];
        for (int i = 0; i < longRows.length; i++) {
            longRows[i] = 40 + (i * 37) % 90; // rows of 5 to 13 lines
        }
        List<int[]> shapes = List.of(
                TablePaginationProbe.uniform(14, 8),  // medium: about half a page
                TablePaginationProbe.uniform(45, 4),  // large: several pages
                longRows);
        List<EbookChapter> chapters = new ArrayList<>();
        int t = 1;
        for (int[] shape : shapes) {
            for (boolean heading : new boolean[]{false, true}) {
                for (int lines = 0; lines <= 36; lines += 3) {
                    String lead = (heading ? "### The columns\n\n" : "") + "The columns map one to one:\n\n";
                    chapters.add(chapter(t, "Filler.\n\n".repeat(lines) + TablePaginationProbe.before(t) + "\n\n"
                            + lead + TablePaginationProbe.table(t, shape)));
                    t++;
                }
            }
        }
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), chapters);

        // A page may stay part-empty only by about one kept-whole row (13 lines at most here).
        TablePaginationProbe.Report report = TablePaginationProbe.inspect(pdf, 220f);
        assertTrue(report.continuations() > 50, "the tables really do cross pages");
        assertEquals(List.of(), report.strandedHeaders());
        assertEquals(List.of(), report.missingRepeatHeaders());
        assertEquals(List.of(), report.splitRows());
        assertEquals(List.of(), report.gaps());
        assertEquals(List.of(), StrandedHeadingProbe.find(pdf), "step 1: headings stay with their table");
    }

    @Test
    void aShortTableStaysWholeWhereverItStarts() throws Exception {
        List<EbookChapter> chapters = new ArrayList<>();
        for (int lines = 0; lines <= 36; lines++) {
            chapters.add(chapter(lines + 1, "Filler.\n\n".repeat(lines)
                    + TablePaginationProbe.table(lines + 1, TablePaginationProbe.uniform(4, 3))));
        }
        TablePaginationProbe.Report report =
                TablePaginationProbe.inspect(service.render(PaginationFixtureBook.ebook(), chapters), 0);

        assertEquals(0, report.continuations(), "a four-row table is never split over two pages");
        assertEquals(List.of(), report.strandedHeaders());
    }

    @Test
    void onlyARowTallerThanAPageIsSplit() throws Exception {
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), List.of(chapter(1,
                TablePaginationProbe.table(1, new int[]{5, 5, 900, 5, 5}))));
        TablePaginationProbe.Report report = TablePaginationProbe.inspect(pdf, 0);

        assertEquals(1, report.splitRows().size(), "the 900-word row cannot fit any page: " + report.splitRows());
        assertTrue(report.splitRows().get(0).contains("row 2"));
        assertEquals(List.of(), report.missingRepeatHeaders());
        assertEquals(List.of(), report.strandedHeaders());
    }

    @Test
    void theStartOfASplittableTableIsGuardedOnAWrapperNeverOnTheTable() {
        EbookContentRenderer renderer = new EbookContentRenderer();
        Document small = Jsoup.parseBodyFragment(renderer.toHtml(TablePaginationProbe.table(1, TablePaginationProbe.uniform(3, 2))));
        assertTrue(small.selectFirst("table").hasClass(TableLayout.WHOLE));
        assertTrue(small.select(".table-start").isEmpty());

        Document large = Jsoup.parseBodyFragment(renderer.toHtml(TablePaginationProbe.table(1, TablePaginationProbe.uniform(40, 4))));
        Element table = large.selectFirst("table");
        assertFalse(table.hasClass(TableLayout.WHOLE));
        assertFalse(table.attr("style").contains("page-break-min-height"),
                "on the table the renderer applies the guard to the rows and strands the header");
        Element wrapper = table.parent();
        assertTrue(wrapper.hasClass("table-start"));
        assertTrue(wrapper.attr("style").startsWith("-fs-page-break-min-height:"));

        Document led = Jsoup.parseBodyFragment(renderer.toHtml("The columns:\n\n" + TablePaginationProbe.table(1, TablePaginationProbe.uniform(40, 4))));
        Element group = led.selectFirst(".keep-with-next--table");
        assertNotNull(group, "a lead-in ending in a colon starts with its table");
        assertEquals("p", group.child(0).tagName());
        assertTrue(TableLayout.startHeight(group.selectFirst("table")) > 0);
        assertTrue(led.select(".table-start").isEmpty(), "the group carries the guard; no second wrapper");
    }

    private static EbookChapter chapter(int n, String content) {
        return EbookChapter.builder().chapterNumber(n).title("Case " + n).content(content).build();
    }
}
