package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Style tab's book type reaches the PDF: the chosen Google fonts are embedded, the spacing applied. */
class BookTypographyTest {

    @Test
    void aBookWithoutChosenTypeKeepsTheClassicFonts() {
        assertNull(BookTypography.of(Ebook.builder().topic("x").build()));
        String html = new EbookHtmlBuilder().build(Ebook.builder().topic("x").title("T").build(),
                List.of(EbookChapter.builder().chapterNumber(1).title("C").content("Text.").build()), "", List.of());
        assertFalse(html.contains("data-book-fonts"));
        assertFalse(html.contains(BookTypography.BODY_FAMILY));
    }

    @Test
    void theChosenTypeIsWrittenIntoTheBook() {
        Ebook ebook = Ebook.builder().topic("x").title("T").bookBodyFont("lora").bookHeadingFont("same")
                .bookLineHeight("airy").bookParagraphSpacing("open").build();
        BookTypography t = BookTypography.of(ebook);
        assertEquals("lora", t.headingKey(), "'same' follows the body font");
        assertEquals("lora:Book Body lora:Book Heading", t.fontSpec());
        String css = t.css();
        assertTrue(css.contains("line-height: 1.72") && css.contains("margin-bottom: 1.15em"));
        String html = new EbookHtmlBuilder().build(ebook,
                List.of(EbookChapter.builder().chapterNumber(1).title("C").content("Text.").build()), "", List.of());
        assertTrue(html.contains("data-book-fonts=\"lora:Book Body lora:Book Heading\""));
    }

    @Test
    void thePdfEmbedsTheChosenFonts() {
        PdfGenerationService service = new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);
        Ebook ebook = Ebook.builder().topic("x").title("The Considered Frame").bookBodyFont("georgia")
                .bookHeadingFont("bodoni").bookLineHeight("normal").bookParagraphSpacing("normal").build();
        EbookChapter chapter = EbookChapter.builder().chapterNumber(1).title("Seeing before shooting")
                .content("## A section\n\n" + "Most weak photographs are failures of *attention*. ".repeat(40)).build();
        byte[] pdf = service.render(ebook, List.of(chapter), List.of());
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        // Georgia can't be embedded, so its open twin Gelasio is.
        assertTrue(raw.contains("Gelasio"), "body font embedded");
        assertTrue(raw.contains("BodoniModa") || raw.contains("Bodoni"), "heading font embedded");
    }
}
