package com.ebookwriter.SaaS.service.ebook;

import com.openhtmltopdf.extend.FSTextBreaker;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableLayoutTest {

    private static final char Z = CodeBreakLineBreaker.ZWSP;

    @Test
    void columnWidthsAlwaysAddUpToTheAvailableWidth() {
        double[][] cases = {
                {50, 60, 70}, {50, 60, 70},       // everything fits naturally
                {40, 60, 50}, {40, 400, 900},     // fits at minimum, not naturally
                {200, 180, 150}, {300, 300, 300}, // even the minimums overflow
        };
        for (int i = 0; i < cases.length; i += 2) {
            double[] w = TableLayout.distribute(cases[i], cases[i + 1], 324);
            assertEquals(324, java.util.Arrays.stream(w).sum(), 0.01);
        }
    }

    @Test
    void spareWidthGoesToTheColumnsThatWantItNotEqually() {
        // METHOD-like column (all short) next to a prose column.
        double[] w = TableLayout.distribute(new double[]{54, 60}, new double[]{54, 900}, 324);
        assertEquals(54, w[0], 0.5, "a short categorical column keeps just what it needs");
        assertEquals(270, w[1], 0.5);
    }

    @Test
    void whenMinimumsOverflowOnlyTheWidestColumnsGiveWay() {
        double[] w = TableLayout.distribute(new double[]{54, 150, 54, 70, 90}, new double[]{54, 270, 140, 120, 200}, 324);
        assertEquals(54, w[0], 0.01, "METHOD keeps its whole word");
        assertEquals(54, w[2], 0.01, "so does AUTH");
        assertTrue(w[1] < 150 && w[4] < 90, "the deficit comes out of the widest columns");
    }

    @Test
    void codeInCellsGetsBreakPointsBetweenWords() {
        assertEquals("Method" + Z + "Argument" + Z + "Not" + Z + "Valid" + Z + "Exception",
                TableLayout.codeBreaks("MethodArgumentNotValidException"));
        assertEquals("EMAIL_" + Z + "ALREADY_" + Z + "REGISTERED", TableLayout.codeBreaks("EMAIL_ALREADY_REGISTERED"));
        assertEquals("Page" + Z + "<Review" + Z + "Response>", TableLayout.codeBreaks("Page<ReviewResponse>"));
        assertEquals("HTTP" + Z + "Server", TableLayout.codeBreaks("HTTPServer"));
        // Never into crumbs: short words and short tails stay whole.
        assertEquals("id", TableLayout.codeBreaks("id"));
        assertEquals("offerId", TableLayout.codeBreaks("offerId"));
        assertEquals("deleted_at", TableLayout.codeBreaks("deleted_at"));
    }

    @Test
    void everyTableGetsAColgroupAndCodeOutsideTablesIsUntouched() {
        String html = new EbookContentRenderer().toHtml("Text with `MethodArgumentNotValidException`.\n\n"
                + "| Exception | Status |\n|---|---|\n| `MethodArgumentNotValidException` | `400 Bad Request` |\n");
        Document doc = Jsoup.parseBodyFragment(html);
        Element table = doc.selectFirst("table");
        assertNotNull(table.selectFirst("> colgroup"));
        double sum = 0;
        for (Element col : table.select("col")) {
            sum += Double.parseDouble(col.attr("style").replaceAll("[^\\d.]", ""));
        }
        assertEquals(100, sum, 0.01);
        assertTrue(html.contains("Method" + Z + "Argument"), "code in a cell gets its break points");
        assertEquals("MethodArgumentNotValidException", doc.selectFirst("p code").text(),
                "running text wraps exactly as before");
    }

    @Test
    void theLineBreakerOnlyAddsBreaksWhereAZeroWidthSpaceWasPlaced() {
        for (String s : List.of("/api/offers/{offerId}/reviews?page=0&size=20", "spring.datasource.url", "plain words here")) {
            assertEquals(breaks(CodeBreakLineBreaker.defaultBreaker(), s), breaks(new CodeBreakLineBreaker(), s),
                    "text without a ZWSP breaks exactly like the renderer's default: " + s);
        }
        assertEquals(List.of(7, 16), breaks(new CodeBreakLineBreaker(), "Method" + Z + "Argument" + Z + "X"));
    }

    @Test
    void pageGeometryIsReadFromTheStylesheet() {
        PageGeometry g = PageGeometry.parse("/* x */ @page { size: 6in 9in; margin: 2cm 1.9cm 2.2cm 1.9cm; @bottom-center {} }");
        assertEquals(432, g.widthPt(), 0.01);
        assertEquals(648, g.heightPt(), 0.01);
        assertEquals(432 - 2 * 1.9 * 72 / 2.54, g.contentWidthPt(), 0.01);
        assertEquals(g.contentWidthPt(), PageGeometry.book().contentWidthPt(), 0.01, "the bundled stylesheet");
        assertEquals(288, PageGeometry.parse("@page { size: 5in 8in; margin: 0.5in; }").contentWidthPt(), 0.01);
    }

    private static List<Integer> breaks(FSTextBreaker b, String s) {
        b.setText(s);
        List<Integer> out = new ArrayList<>();
        for (int i = b.next(); i != BreakIterator.DONE && i < s.length(); i = b.next()) {
            out.add(i);
        }
        return out;
    }
}
