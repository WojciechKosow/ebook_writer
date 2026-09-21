package com.ebookwriter.SaaS.dto.cover;

/**
 * A cover composition variant. The cover is never a single flattened image: it is
 * a layout that arranges <em>separate, editable</em> elements — the AI-generated
 * (text-free) visual, the title, the subtitle, an optional author byline and the
 * publisher imprint. The variant decides where the visual sits and where the
 * typography's <b>safe area</b> is, and the image-generation prompt is written to
 * leave negative space in exactly that area.
 *
 * <p>Selected by {@link com.ebookwriter.SaaS.service.ebook.CoverPlanningService}
 * (the model reasons about subject/tone and picks one) and rendered identically
 * in the editor preview and the PDF by {@code EbookHtmlBuilder} + {@code
 * ebook.css}. {@link #TYPOGRAPHIC} needs no image and is the safe fallback when a
 * visual is missing or generation fails.
 */
public enum CoverLayout {

    /** Large visual in the upper field, strong title below it on paper, generous whitespace. */
    EDITORIAL(true),

    /** The visual fills the page; typography sits in a controlled scrim band at the foot. */
    IMAGE_LED(true),

    /** Visual and typography occupy distinct stacked regions (image top, text panel below). */
    SPLIT(true),

    /** A small, framed visual with strong typography and negative space. */
    MINIMAL(true),

    /** No image (or optional) — the composed title page with a structural motif. */
    TYPOGRAPHIC(false);

    private final boolean requiresVisual;

    CoverLayout(boolean requiresVisual) {
        this.requiresVisual = requiresVisual;
    }

    /** Whether this layout needs an AI/uploaded visual to render correctly. */
    public boolean requiresVisual() {
        return requiresVisual;
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
