package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block formatting written by the Studio editor must reach the PDF: attribute
 * lines ({@code {.dropcap .align-center}}), quotation sources and styles,
 * divider styles, the Warning callout, pull-quote sources and figure alignment.
 */
class EditorFormattingRenderingTest {

    private static final String MD = """
            Every photograph is a negotiation with light.

            A centred line with a [link](https://example.com/guide).
            {.align-center}

            # A part title
            {.align-center}

            ## Right heading {.align-right}

            A paragraph the author gave an initial.
            {.dropcap}

            > You don’t take a photograph, you make it.
            >
            > — Ansel Adams
            {.quote-rule}

            > A plain quotation.

            ---

            ---
            {.divider-ornament}

            ---
            {.divider-dots}

            :::pullquote
            Exposure is a decision.
            — Elise Moreau
            :::

            :::warning
            Set an ISO ceiling.
            :::

            :::common-mistake
            Fighting the light.
            :::

            ```
            {.dropcap}
            ```
            """;

    private Document render(String md) {
        RenderContext ctx = new RenderContext();
        ctx.startChapter(3);
        ctx.withAutoDropCap(true);
        return Jsoup.parseBodyFragment(new EbookContentRenderer().toHtml(md, ctx));
    }

    @Test
    void attributeLinesBecomeClassesAndVanishFromTheText() {
        Document doc = render(MD);
        doc.select("pre").remove();
        String text = doc.body().text();
        assertFalse(text.contains("{.") || text.contains("⟦"), "no attribute syntax or marker is printed: " + text);

        Element centred = doc.selectFirst("p:contains(A centred line)");
        assertTrue(centred.hasClass("align-center"));
        assertNotNull(centred.selectFirst("a[href=https://example.com/guide]"), "links stay links");
        assertTrue(doc.selectFirst("h1:contains(A part title)").hasClass("align-center"));
        assertTrue(doc.selectFirst("h2:contains(Right heading)").hasClass("align-right"));
        assertEquals("Right heading", doc.selectFirst("h2").text());

        Element marked = doc.selectFirst("p:contains(gave an initial)");
        assertTrue(marked.hasClass("dropcap"));
        assertEquals("A", marked.selectFirst("span.dropcap-letter").text());
        // As in the editor, the chapter's opening paragraph gets an initial too.
        assertEquals("E", doc.selectFirst("p:contains(negotiation)").selectFirst("span.dropcap-letter").text());

        // The attribute inside a code fence is code, not formatting.
        assertTrue(render(MD).selectFirst("pre").text().contains("{.dropcap}"));
    }

    @Test
    void quotesDividersAndCallouts() {
        Document doc = render(MD);
        Element rule = doc.selectFirst("blockquote.quote-rule");
        assertNotNull(rule, "the left-rule style is kept");
        assertEquals("— Ansel Adams", rule.selectFirst("p.quote-source").text());
        assertFalse(doc.select("blockquote").get(1).hasClass("quote-rule"), "a plain quote keeps the editorial style");

        assertNotNull(doc.selectFirst("div.divider--line"));
        assertNotNull(doc.selectFirst("div.divider--ornament span.orn-dia"));
        assertEquals("· · ·", doc.selectFirst("div.divider--dots").text());

        assertEquals("— Elise Moreau", doc.selectFirst("div.cmp--pullquote div.cmp-source").text());
        assertFalse(doc.selectFirst("div.cmp--pullquote div.cmp-body").text().contains("Elise"));
        assertEquals("WARNING", doc.selectFirst("div.cmp--warning .cmp-label").text());
        assertEquals("COMMON MISTAKE", doc.selectFirst("div.cmp--mistake .cmp-label").text());
    }

    @Test
    void openingParagraphCanOptOutOfTheInitial() {
        Document doc = render("No initial here.\n{.no-dropcap}\n\nSecond.");
        assertNull(doc.selectFirst("span.dropcap-letter"));
        assertEquals("No initial here.", doc.selectFirst("p").text());
    }

    @Test
    void figuresAlignAndHideAnEmptyCaption() {
        UUID id = UUID.randomUUID();
        String md = "Text.\n\n<figure class=\"ebook-figure\" data-layout=\"column\" data-width=\"60\" data-align=\"left\" "
                + "data-aspect=\"orig\" data-focal=\"0.50,0.50\">\n  <img src=\"ebook-image:" + id + "\" alt=\"\">\n</figure>\n\nMore.";
        Element fig = render(md).selectFirst("figure.figure--column");
        assertTrue(fig.hasClass("figure--align-left"));
        assertTrue(fig.attr("style").contains("width:60%"));
        assertNull(fig.selectFirst("figcaption"), "no caption, no caption line");
    }

    @Test
    void theFormattedChapterRendersIntoAValidPdf() {
        PdfGenerationService service = new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);
        Ebook ebook = Ebook.builder().topic("Photography").title("The Considered Frame").build();
        EbookChapter chapter = EbookChapter.builder().chapterNumber(1).title("Seeing").content(MD + "\n\n" + "More text. ".repeat(80)).build();
        byte[] pdf = service.render(ebook, List.of(chapter), List.of());
        assertTrue(pdf.length > 1000 && pdf[0] == '%', "a validated PDF is produced");
    }
}
