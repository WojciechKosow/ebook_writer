package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real PDFs: every table — wide, code-heavy, many-columned — stays inside
 * the text column, and the inspector reports a table that doesn't.
 */
class TableWidthPdfTest {

    private final PdfGenerationService service =
            new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);

    @Test
    void technicalTablesStayInsideTheTextColumn() throws Exception {
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), List.of(EbookChapter.builder()
                .chapterNumber(1).title("Tables").content(TableFixtures.tables()).build()));

        assertEquals(List.of(), violations(pdf));
        assertFalse(PdfQualityInspector.inspect(pdf, -1).issues().stream()
                .anyMatch(i -> i.message().contains("outside the text column")));
    }

    @Test
    void theInspectorReportsATableWiderThanTheTextColumn() throws Exception {
        // A table forced past the margin (as auto layout used to do) must not pass silently.
        String wide = "<table style=\"width: 160%\"><tr><td>one</td><td>two</td><td>three</td><td>beyond</td></tr></table>";
        // Rendered without the layout validation pass (which would repair it), to
        // check the inspector on its own.
        byte[] pdf = service.layoutAndWrite(org.jsoup.Jsoup.parse(service.html(PaginationFixtureBook.ebook(),
                List.of(EbookChapter.builder().chapterNumber(1).title("Wide").content(wide).build()))),
                java.util.Map.of()).pdf();

        assertFalse(violations(pdf).isEmpty());
        assertTrue(PdfQualityInspector.inspect(pdf, -1).issues().stream()
                .anyMatch(i -> i.message().contains("outside the text column")));
    }

    private static List<String> violations(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf)) {
            for (int i = 1; i < doc.getNumberOfPages(); i++) {
                for (String v : PdfQualityInspector.contentAreaViolations(doc, i, PageGeometry.book())) {
                    out.add("p" + (i + 1) + " " + v);
                }
            }
        }
        return out;
    }
}
