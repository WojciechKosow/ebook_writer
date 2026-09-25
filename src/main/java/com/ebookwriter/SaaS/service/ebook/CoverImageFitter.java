package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.image.AspectRatio;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Fits a cover visual to its layout region <b>without distortion</b>, by
 * cropping it to the region's exact aspect ratio around its focal point.
 *
 * <p>Why this exists: the PDF renderer (openhtmltopdf) does not honour CSS
 * {@code object-fit}, so an image whose shape differs from its region — typically
 * a user-uploaded cover — would be <em>stretched</em> into it. Cropping the pixels
 * first makes the region and the image the same shape, so neither the PDF nor the
 * browser preview can distort it, and both show the identical crop.
 *
 * <ul>
 *   <li>An image already at the region ratio (every AI cover, which is generated
 *       at exactly that ratio) is returned <b>byte-for-byte unchanged</b> — no
 *       decode, no recompression.</li>
 *   <li>Otherwise the largest region-shaped window is cut at full source
 *       resolution (no scaling) around the focal point and encoded once as
 *       lossless PNG. The stored asset itself is never modified.</li>
 *   <li>Anything undecodable (SVG, an unsupported WebP) is returned unchanged.</li>
 * </ul>
 */
@Slf4j
public final class CoverImageFitter {

    private CoverImageFitter() {
    }

    /** Ratio drift below which an image is considered already fitted (sub-pixel rounding). */
    static final double RATIO_TOLERANCE = 0.01;

    /** The fitted bytes and their MIME type. */
    public record Fitted(byte[] bytes, String contentType, boolean cropped) {
    }

    /**
     * @param focalX percent of width to centre the crop on (null = 50)
     * @param focalY percent of height to centre the crop on (null = 50)
     */
    public static Fitted fit(byte[] bytes, String contentType, AspectRatio region, Integer focalX, Integer focalY) {
        if (bytes == null || region == null) {
            return new Fitted(bytes, contentType, false);
        }
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            src = null;
        }
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return new Fitted(bytes, contentType, false);
        }
        int[] window = cropWindow(src.getWidth(), src.getHeight(), region.ratio(), focalX, focalY);
        if (window == null) {
            return new Fitted(bytes, contentType, false);
        }
        try {
            BufferedImage cropped = src.getSubimage(window[0], window[1], window[2], window[3]);
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
            ImageIO.write(cropped, "png", out);
            return new Fitted(out.toByteArray(), "image/png", true);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not crop cover image to its region; using it as-is: {}", e.getMessage());
            return new Fitted(bytes, contentType, false);
        }
    }

    /**
     * The crop window {@code [x, y, width, height]} of the largest {@code ratio}
     * (w/h) rectangle inside a {@code w×h} image, centred on the focal point and
     * clamped to the image; null when the image already has that ratio.
     */
    static int[] cropWindow(int w, int h, double ratio, Integer focalX, Integer focalY) {
        double actual = (double) w / h;
        if (Math.abs(actual - ratio) / ratio <= RATIO_TOLERANCE) {
            return null;
        }
        int cw = w;
        int ch = h;
        if (actual > ratio) {
            cw = Math.max(1, (int) Math.round(h * ratio)); // too wide: trim the sides
        } else {
            ch = Math.max(1, (int) Math.round(w / ratio)); // too tall: trim top/bottom
        }
        double fx = (focalX == null ? 50 : Math.max(0, Math.min(100, focalX))) / 100.0;
        double fy = (focalY == null ? 50 : Math.max(0, Math.min(100, focalY))) / 100.0;
        int x = (int) Math.round(fx * w - cw / 2.0);
        int y = (int) Math.round(fy * h - ch / 2.0);
        x = Math.max(0, Math.min(w - cw, x));
        y = Math.max(0, Math.min(h - ch, y));
        return new int[]{x, y, cw, ch};
    }
}
