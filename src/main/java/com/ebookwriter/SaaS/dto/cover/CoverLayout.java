package com.ebookwriter.SaaS.dto.cover;

import com.ebookwriter.SaaS.dto.image.AspectRatio;

/**
 * A cover composition variant. The cover is never a single flattened image: it is
 * a layout that arranges <em>separate, editable</em> elements — the AI-generated
 * (text-free) visual, the title, the subtitle, an optional author byline and the
 * publisher imprint. The variant decides where the visual sits and where the
 * typography's <b>safe area</b> is, and the image-generation prompt is written to
 * leave negative space in exactly that area.
 *
 * <p>Crucially, each visual layout also fixes the <b>aspect ratio of the image
 * region</b> it renders the visual into (see {@link #imageAspectRatio()}). The
 * cover visual is generated at exactly that ratio so it fills its region with no
 * distortion and only incidental cropping — the image is prepared <em>for</em> the
 * composition rather than generated as a generic square and squashed to fit. The
 * CSS image regions in {@code ebook.css} match these ratios one-to-one.
 *
 * <p>Selected by {@link com.ebookwriter.SaaS.service.ebook.CoverPlanningService}
 * (the model reasons about subject/tone and picks one) and rendered identically
 * in the editor preview and the PDF by {@code EbookHtmlBuilder} + {@code
 * ebook.css}. {@link #TYPOGRAPHIC} needs no image and is the safe fallback when a
 * visual is missing or generation fails.
 */
public enum CoverLayout {

    /** Large visual in the upper field, strong title below it on paper, generous whitespace. */
    EDITORIAL(true, AspectRatio.LANDSCAPE),

    /** The visual fills the page; typography sits in a controlled scrim band at the foot. */
    IMAGE_LED(true, AspectRatio.PORTRAIT),

    /** Visual and typography occupy distinct stacked regions (image top, text panel below). */
    SPLIT(true, AspectRatio.LANDSCAPE),

    /** A small, framed visual with strong typography and negative space. */
    MINIMAL(true, AspectRatio.LANDSCAPE),

    /** No image (or optional) — the composed title page with a structural motif. */
    TYPOGRAPHIC(false, null);

    private final boolean requiresVisual;
    private final AspectRatio imageAspectRatio;

    CoverLayout(boolean requiresVisual, AspectRatio imageAspectRatio) {
        this.requiresVisual = requiresVisual;
        this.imageAspectRatio = imageAspectRatio;
    }

    /** Whether this layout needs an AI/uploaded visual to render correctly. */
    public boolean requiresVisual() {
        return requiresVisual;
    }

    /**
     * The aspect ratio the visual must be generated at so it fills this layout's
     * image region without distortion — the region in {@code ebook.css} is sized
     * to the same ratio. Null for {@link #TYPOGRAPHIC}, which has no image region;
     * a visual layout always has one, so callers guard on {@link #requiresVisual()}.
     */
    public AspectRatio imageAspectRatio() {
        return imageAspectRatio;
    }

    /** Lenient parse from model/string input; unknown/blank falls back to EDITORIAL. */
    public static CoverLayout fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return EDITORIAL;
        }
        String s = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        try {
            return CoverLayout.valueOf(s);
        } catch (IllegalArgumentException e) {
            return EDITORIAL;
        }
    }
}
