package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the image path of the PDF renderer offline: a cover image and an
 * inline {@code ebook-image:<id>} reference must be rewritten to the {@code r2:}
 * scheme and their bytes streamed from storage into the embedded PDF. A stubbed
 * {@link R2StorageService} supplies a real in-memory PNG, so this covers the
 * custom-protocol wiring end to end without touching R2.
 */
class PdfImageRenderingTest {

    @Test
    void embedsCoverAndInlineImagesFromStorage() throws IOException {
        R2StorageService storage = mock(R2StorageService.class);
        byte[] png = tinyPng();
        when(storage.download(anyString())).thenReturn(Optional.of(png));

        PdfGenerationService service =
                new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, storage);

        Ebook ebook = Ebook.builder()
                .topic("Illustrated").title("An Illustrated Book").build();

        UUID coverId = UUID.randomUUID();
        UUID inlineId = UUID.randomUUID();
        EbookImage cover = EbookImage.builder()
                .id(coverId).ebook(ebook).role(EbookImageRole.COVER)
                .storageKey("ebooks/x/images/" + coverId + ".png").contentType("image/png").build();
        EbookImage inline = EbookImage.builder()
                .id(inlineId).ebook(ebook).role(EbookImageRole.INLINE)
                .storageKey("ebooks/x/images/" + inlineId + ".png").contentType("image/png").build();

        EbookChapter chapter = EbookChapter.builder()
                .chapterNumber(1)
                .title("With a picture")
                .content("Some text.\n\n![a picture](" + inline.markdownRef() + ")\n\nMore text.")
                .build();

        byte[] pdf = service.render(ebook, List.of(chapter), List.of(cover, inline));

        assertTrue(pdf.length > 1000, "PDF should be produced");
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "Output should start with the %PDF magic header");
        // Both images resolved to r2: keys and were pulled from storage.
        verify(storage, times(2)).download(anyString());
    }

    @Test
    void dropsUnresolvedImageReferencesWithoutTouchingStorage() {
        R2StorageService storage = mock(R2StorageService.class);

        PdfGenerationService service =
                new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, storage);

        Ebook ebook = Ebook.builder().topic("Broken").title("Broken Refs").build();
        EbookChapter chapter = EbookChapter.builder()
                .chapterNumber(1)
                .title("Dangling")
                // References an image id that isn't in the provided image list.
                .content("Text.\n\n![gone](ebook-image:" + UUID.randomUUID() + ")\n\nEnd.")
                .build();

        byte[] pdf = service.render(ebook, List.of(chapter), List.of());

        assertTrue(pdf.length > 1000, "PDF should still render");
        verify(storage, never()).download(anyString());
    }

    private static byte[] tinyPng() throws IOException {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.decode("#3366cc"));
        g.fillRect(0, 0, 120, 80);
        g.dispose();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ImageIO.write(img, "png", os);
        return os.toByteArray();
    }
}
