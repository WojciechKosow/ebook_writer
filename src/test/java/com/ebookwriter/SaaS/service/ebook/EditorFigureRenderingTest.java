package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Photos placed in the Studio editor ({@code <figure class="ebook-figure"
 * data-layout data-width data-aspect data-focal>}) must reach the PDF as laid
 * out on the page: full bleed, in the column at a width, or wrapped left/right,
 * numbered "Fig. chapter.n" with their caption, and cropped exactly as the
 * editor framed them.
 */
class EditorFigureRenderingTest {

    private static String figure(UUID id, String layout, int width, String aspect, String caption) {
        return "<figure class=\"ebook-figure\" data-layout=\"" + layout + "\" data-width=\"" + width
                + "\" data-aspect=\"" + aspect + "\" data-focal=\"0.25,0.50\">\n"
                + "  <img src=\"ebook-image:" + id + "\" alt=\"A photo\">\n"
                + "  <figcaption>" + caption + "</figcaption>\n</figure>";
    }

    @Test
    void editorFiguresBecomePlacedNumberedFigures() {
        UUID id = UUID.randomUUID();
        String md = "Intro paragraph.\n\n" + figure(id, "right", 42, "4:5", "Birds &amp; foliage.")
                + "\n\nText that wraps.\n\n" + figure(id, "full", 100, "16:9", "Late light.") + "\n\n---\n\nEnd.";
        RenderContext ctx = new RenderContext();
        ctx.startChapter(2);
        Document doc = Jsoup.parseBodyFragment(new EbookContentRenderer().toHtml(md, ctx));

        Element right = doc.selectFirst("figure.figure--right");
        assertNotNull(right, "a wrapped photo is floated right");
        assertTrue(right.attr("style").contains("width:42%"));
        assertEquals("4:5@0.25,0.50", right.selectFirst("img").attr("data-crop"));
        assertEquals("Fig. 2.1", right.selectFirst(".figure-num").text());
        assertTrue(right.selectFirst("figcaption").text().endsWith("Birds & foliage."));

        Element full = doc.selectFirst("figure.figure--full");
        assertNotNull(full, "a full-bleed photo keeps its placement");
        assertTrue(full.hasClass("bleed") && full.selectFirst("img").hasClass("bleed"));
        assertEquals("Fig. 2.2", full.selectFirst(".figure-num").text());
        assertNull(doc.selectFirst("figure.ebook-figure"), "no raw editor figure remains");
        assertNotNull(doc.selectFirst("div.divider--line"), "a bare --- is the thin-line divider");
    }

    @Test
    void rendersEveryPlacementIntoAValidPdf() throws IOException {
        R2StorageService storage = mock(R2StorageService.class);
        when(storage.download(anyString())).thenReturn(Optional.of(png(900, 600)));
        PdfGenerationService service = new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, storage);

        Ebook ebook = Ebook.builder().topic("Photography").title("The Considered Frame").build();
        UUID id = UUID.randomUUID();
        EbookImage photo = EbookImage.builder().id(id).ebook(ebook)
                .role(EbookImageRole.GENERAL).placement(EbookImagePlacement.CHAPTER)
                .storageKey("ebooks/x/images/" + id + ".png").contentType("image/png").build();
        String para = "Aperture is the opening inside the lens, written as an f-number. Small numbers mean a wide "
                + "opening and a lot of light; large numbers mean a narrow opening and very little. ";
        String md = figure(id, "full", 100, "16:9", "Late light over the bay.") + "\n\n" + para.repeat(3)
                + "\n\n" + figure(id, "right", 40, "4:5", "Shallow depth of field.") + "\n\n" + para.repeat(4)
                + "\n\n" + figure(id, "left", 36, "2:3", "Leading lines.") + "\n\n" + para.repeat(4)
                + "\n\n" + figure(id, "column", 70, "orig", "") + "\n\n" + para;
        EbookChapter chapter = EbookChapter.builder().chapterNumber(1).title("The exposure triangle").content(md).build();

        byte[] pdf = service.render(ebook, List.of(chapter), List.of(photo));
        assertTrue(pdf.length > 1000 && pdf[0] == '%' && pdf[1] == 'P', "a validated PDF is produced");
    }

    @Test
    void framingCropsLikeObjectPosition() throws IOException {
        // 900×600 to 1:1 leaves 300 px of free width; fx = 0.25 puts 75 px of it on the left.
        int[] w = CoverImageFitter.positionedWindow(900, 600, 1.0, 0.25, 0.5);
        assertArrayEquals(new int[]{75, 0, 600, 600}, w);
        assertNull(CoverImageFitter.positionedWindow(900, 600, 1.5, 0.5, 0.5), "already the right shape");

        CoverImageFitter.Fitted f = CoverImageFitter.fitFramed(png(900, 600), "image/png", "4:5@0.5,0.5");
        assertTrue(f.cropped());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(f.bytes()));
        assertEquals(480, out.getWidth());
        assertEquals(600, out.getHeight());
        assertTrue(!CoverImageFitter.fitFramed(png(900, 600), "image/png", "nonsense").cropped());
    }

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.decode("#4d7c8a"));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ImageIO.write(img, "png", os);
        return os.toByteArray();
    }
}
