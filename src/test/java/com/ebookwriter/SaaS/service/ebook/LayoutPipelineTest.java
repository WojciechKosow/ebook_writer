package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The render pipeline end to end, on real layouts:
 * <pre>
 *   layout → validate → PASS → write the PDF
 *                     → repairable → repair the source → layout → validate → …
 *                     → unrecoverable / repair didn't help → controlled failure
 * </pre>
 */
class LayoutPipelineTest {

    private final PdfGenerationService service =
            new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);

    private static EbookChapter chapter(int n, String content) {
        return EbookChapter.builder().chapterNumber(n).title("Chapter " + n).content(content).build();
    }

    private PdfGenerationService.Rendered render(String content) {
        return service.renderInspected(PaginationFixtureBook.ebook(), List.of(chapter(1, content)));
    }

    private static String pngDataUri(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String code(int lines) {
        StringBuilder sb = new StringBuilder("<pre>");
        for (int i = 0; i < lines; i++) {
            sb.append("int field").append(i).append(";\n");
        }
        return sb.append("</pre>").toString();
    }

    /** No text or painted shape of a body page outside the text column, read back from the PDF itself. */
    private static void assertInsideTheColumn(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            for (int i = 1; i < doc.getNumberOfPages(); i++) {
                assertEquals(List.of(), PdfQualityInspector.contentAreaViolations(doc, i, PageGeometry.book()),
                        "page " + (i + 1));
            }
        }
    }

    // ---- PASS ---------------------------------------------------------------------------------

    @Test
    void aRealisticBookPassesOnItsFirstLayout() throws Exception {
        List<EbookChapter> chapters = new ArrayList<>(PaginationFixtureBook.chapters(1, 10));
        // Images and a table several pages long, next to the generated headings,
        // prose, lists, code (some over a page), callouts, exercises and tables.
        StringBuilder longTable = new StringBuilder("The full schema:\n\n| Column | Type | Meaning |\n|---|---|---|\n");
        for (int i = 0; i < 80; i++) {
            longTable.append("| `field").append(i).append("` | VARCHAR(255) | What field ").append(i).append(" holds. |\n");
        }
        chapters.add(chapter(11, "Opening.\n\n## Architecture\n\n![The request flow](" + pngDataUri(600, 400)
                + ")\n\nThe diagram shows the flow.\n\n" + longTable + "\n\n### Afterwards\n\nClosing words."));

        PdfGenerationService.Rendered r = service.renderInspected(PaginationFixtureBook.ebook(), chapters);

        assertEquals(LayoutValidationResult.Status.PASS, r.validation().status());
        assertEquals(List.of(), r.validation().errors());
        assertEquals(1, r.attempts(), "nothing to repair: the first layout is the final PDF");
        assertTrue(r.layout().pages().size() > 150);
        assertTrue(r.layout().tables().stream().anyMatch(t -> t.rows().stream()
                .map(row -> r.layout().pageAt(row.rect().top()).number()).distinct().count() > 2),
                "the long table runs over several pages");
        assertInsideTheColumn(r.pdf());
        assertEquals(List.of(), StrandedHeadingProbe.findTitles(r.pdf()));
    }

    // ---- REPAIRABLE → repaired → PASS ----------------------------------------------------

    @Test
    void aTableWiderThanThePageIsRepairedAndRevalidated() throws Exception {
        String wide = "<table style=\"width: 160%\"><tr><td>one</td><td>two</td><td>three</td><td>beyond the margin</td></tr></table>";
        PdfGenerationService.Rendered unrepaired = service.layoutAndWrite(org.jsoup.Jsoup.parse(
                service.html(PaginationFixtureBook.ebook(), List.of(chapter(1, "Opening.\n\n" + wide)))), java.util.Map.of());
        assertTrue(unrepaired.validation().errors().stream().anyMatch(i -> i.type() == LayoutIssue.Type.TABLE_OVERFLOW));

        PdfGenerationService.Rendered r = render("Opening.\n\n" + wide);

        assertEquals(LayoutValidationResult.Status.PASS, r.validation().status());
        assertEquals(2, r.attempts(), "detected, repaired at the source, laid out again, revalidated");
        assertInsideTheColumn(r.pdf());
    }

    @Test
    void anImageTallerThanThePageIsCappedAndRevalidated() throws Exception {
        PdfGenerationService.Rendered r = render("Opening.\n\n<div><img src=\"" + pngDataUri(100, 400)
                + "\" style=\"width: 200pt; height: 800pt\"/></div>\n\nAfter.");

        assertEquals(LayoutValidationResult.Status.PASS, r.validation().status());
        assertEquals(2, r.attempts());
        LayoutSnapshot.Block img = r.layout().blocks().stream().filter(b -> b.tag().equals("img")).findFirst().orElseThrow();
        assertTrue(img.rect().height() <= PageGeometry.book().contentHeightPt(), "the image now fits a page");
    }

    @Test
    void anOrphanHeadingIsMovedToItsContentAndRevalidated() {
        // A heading whose keep-with-next was switched off, low on the page, above a
        // code block that must move whole: left alone, the heading ends the page.
        String content = "Opening.\n\n<div style=\"height: 380pt\"></div>\n\n<div><h3 style=\"page-break-after: auto\">"
                + "Creating the JWT Provider</h3>" + code(16) + "</div>\n\nAfter.";
        PdfGenerationService.Rendered unrepaired = service.layoutAndWrite(org.jsoup.Jsoup.parse(
                service.html(PaginationFixtureBook.ebook(), List.of(chapter(1, content)))), java.util.Map.of());
        assertTrue(unrepaired.validation().errors().stream().anyMatch(i -> i.type() == LayoutIssue.Type.ORPHAN_HEADING));

        PdfGenerationService.Rendered r = render(content);

        assertEquals(LayoutValidationResult.Status.PASS, r.validation().status());
        assertEquals(2, r.attempts());
        LayoutSnapshot layout = r.layout();
        int headingPage = layout.lines().stream().filter(l -> l.fragments().stream().anyMatch(f -> f.text().contains("JWT Provider")))
                .map(l -> layout.pageAt(l.rect().top()).number()).findFirst().orElseThrow();
        int codePage = layout.lines().stream().filter(l -> l.fragments().stream().anyMatch(f -> f.text().contains("field0;")))
                .map(l -> layout.pageAt(l.rect().top()).number()).findFirst().orElseThrow();
        assertEquals(codePage, headingPage, "the heading now opens the page its code starts on");
    }

    // ---- FAILED → controlled failure -------------------------------------------------------

    @Test
    void contentThatNeverReachesAPageFailsWithoutAPdf() {
        // Pinned beyond the end of the document: the renderer would silently drop it.
        String content = "Opening.\n\n<div style=\"position: relative\"><p style=\"position: absolute; top: 460pt; "
                + "left: 0; width: 200pt\">Pinned past the last page. Nobody would ever read this.</p></div>\n\nAfter.";

        LayoutValidationException e = assertThrows(LayoutValidationException.class, () -> render(content));

        assertEquals(1, e.attempts(), "no deterministic repair exists: stop at once");
        assertEquals(LayoutValidationResult.Status.FAILED, e.result().status());
        assertTrue(e.diagnostics().contains("CONTENT_OVERFLOW"), e.diagnostics());
        assertTrue(e.diagnostics().contains("Result: FAILED"), e.diagnostics());
        assertTrue(e instanceof EbookValidationException, "the generation pipeline fails the book and refunds the hold");
    }

    @Test
    void aRepairThatDoesNotHelpIsNotRetriedForever() {
        // min-width beats max-width: the width repair cannot shrink this block.
        String content = "Opening.\n\n<div style=\"min-width: 520pt\">A block that insists on being wider than "
                + "the page, whatever it is told.</div>\n\nAfter.";

        LayoutValidationException e = assertThrows(LayoutValidationException.class, () -> render(content));

        assertEquals(2, e.attempts(), "one repair, one revalidation, the same error again: stop");
        assertTrue(e.attempts() <= PdfGenerationService.MAX_REPAIR_ATTEMPTS + 1);
        assertTrue(e.diagnostics().contains("Repair OUTSIDE_PAGE_BOUNDS"), e.diagnostics());
        assertTrue(e.diagnostics().contains("Result: FAILED"), e.diagnostics());
    }

    // ---- through renderAndStore ------------------------------------------------------------

    private record Stored(PdfGenerationService service, EbookPdfRepository pdfs) {
    }

    private static Stored storing(UUID id, String content) {
        EbookRepository ebooks = mock(EbookRepository.class);
        EbookChapterRepository chapters = mock(EbookChapterRepository.class);
        EbookImageRepository images = mock(EbookImageRepository.class);
        EbookPdfRepository pdfs = mock(EbookPdfRepository.class);
        Ebook ebook = PaginationFixtureBook.ebook();
        when(ebooks.findById(id)).thenReturn(Optional.of(ebook));
        when(chapters.findByEbookIdOrderByChapterNumberAsc(id)).thenReturn(List.of(chapter(1, content)));
        when(images.findByEbookIdOrderByCreatedAtAsc(id)).thenReturn(List.of());
        return new Stored(new PdfGenerationService(ebooks, chapters, pdfs, new EbookHtmlBuilder(), images, null), pdfs);
    }

    @Test
    void renderAndStoreStoresOnlyAValidatedPdf() {
        UUID id = UUID.randomUUID();
        Stored ok = storing(id, "Opening.\n\n<table style=\"width: 160%\"><tr><td>a</td><td>b</td><td>c</td>"
                + "<td>beyond</td></tr></table>\n\nAfter.");
        int pages = ok.service().renderAndStore(id, 0);
        assertTrue(pages > 0);
        verify(ok.pdfs()).save(any(EbookPdf.class)); // repaired, revalidated, stored

        UUID broken = UUID.randomUUID();
        Stored bad = storing(broken, "Opening.\n\n<div style=\"position: relative\"><p style=\"position: absolute; "
                + "top: 460pt; left: 0; width: 200pt\">Lost.</p></div>\n\nAfter.");
        assertThrows(LayoutValidationException.class, () -> bad.service().renderAndStore(broken, 0));
        verify(bad.pdfs(), never()).save(any(EbookPdf.class)); // a broken PDF is never stored as a success
    }
}
