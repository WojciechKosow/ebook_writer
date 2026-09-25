package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.image.AspectRatio;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers are cropped to their region's shape (never stretched), around the focal point. */
class CoverImageFitterTest {

    @Test
    void anImageAlreadyAtTheRegionRatioIsReturnedByteForByte() throws IOException {
        byte[] png = png(1024, 1536);
        CoverImageFitter.Fitted f = CoverImageFitter.fit(png, "image/png", AspectRatio.PORTRAIT, null, null);
        assertFalse(f.cropped());
        assertArrayEquals(png, f.bytes(), "no decode/re-encode of an already-fitted AI cover");
    }

    @Test
    void aSquareUploadIsCroppedToPortraitAtFullResolution() throws IOException {
        CoverImageFitter.Fitted f = CoverImageFitter.fit(png(1200, 1200), "image/jpeg", AspectRatio.PORTRAIT, null, null);
        assertTrue(f.cropped());
        assertEquals("image/png", f.contentType(), "cropped once, encoded losslessly");
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(f.bytes()));
        assertEquals(800, out.getWidth());
        assertEquals(1200, out.getHeight(), "full source height kept — no downscaling");
    }

    @Test
    void theCropWindowFollowsTheFocalPointAndStaysInsideTheImage() {
        assertArrayEquals(new int[]{200, 0, 800, 1200}, CoverImageFitter.cropWindow(1200, 1200, 2.0 / 3, null, null));
        assertArrayEquals(new int[]{0, 0, 800, 1200}, CoverImageFitter.cropWindow(1200, 1200, 2.0 / 3, 0, 50));
        assertArrayEquals(new int[]{400, 0, 800, 1200}, CoverImageFitter.cropWindow(1200, 1200, 2.0 / 3, 100, 50));
        // A tall image into a landscape region trims top/bottom around focalY.
        assertArrayEquals(new int[]{0, 0, 900, 600}, CoverImageFitter.cropWindow(900, 1600, 1.5, 50, 0));
        assertNull(CoverImageFitter.cropWindow(1536, 1024, 1.5, 50, 50));
    }

    @Test
    void undecodableBytesAreLeftUntouched() {
        byte[] svg = "<svg/>".getBytes();
        CoverImageFitter.Fitted f = CoverImageFitter.fit(svg, "image/svg+xml", AspectRatio.PORTRAIT, 50, 50);
        assertFalse(f.cropped());
        assertArrayEquals(svg, f.bytes());
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
