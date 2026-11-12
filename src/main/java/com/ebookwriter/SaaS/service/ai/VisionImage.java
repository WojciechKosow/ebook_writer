package com.ebookwriter.SaaS.service.ai;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * An image prepared for Claude to look at: downscaled so its long edge is at
 * most {@link #MAX_EDGE} pixels (enough to judge light, composition and content,
 * and about a thousand input tokens) and re-encoded as JPEG. Formats Claude can't
 * read (SVG) and files that can't be decoded are left out — the caller falls
 * back to describing the image by its name and the author's note.
 *
 * @param label     how the prompt refers to the image (its id)
 * @param mediaType the encoded type ({@code image/jpeg}, or {@code image/webp} when passed through)
 * @param bytes     the encoded image
 */
public record VisionImage(String label, String mediaType, byte[] bytes) {

    /** Longest edge sent to the model, in pixels. */
    public static final int MAX_EDGE = 1024;

    /** Largest undecodable file passed through as-is (the API accepts up to 5 MB per image). */
    static final int MAX_PASSTHROUGH_BYTES = 3_500_000;

    /**
     * Prepare {@code data} for the model, or empty when it can't be shown
     * (SVG, unreadable, or too large to pass through).
     */
    public static Optional<VisionImage> prepare(String label, byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.contains("svg")) {
            return Optional.empty();
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            source = null;
        }
        if (source == null) {
            // No decoder for it here (e.g. WebP), but the model reads it natively.
            if (type.equals("image/webp") && data.length <= MAX_PASSTHROUGH_BYTES) {
                return Optional.of(new VisionImage(label, "image/webp", data));
            }
            return Optional.empty();
        }
        try {
            return Optional.of(new VisionImage(label, "image/jpeg", toJpeg(source)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static byte[] toJpeg(BufferedImage source) throws IOException {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE); // transparent PNGs on white, as on the page
            g.fillRect(0, 0, tw, th);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(out, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
