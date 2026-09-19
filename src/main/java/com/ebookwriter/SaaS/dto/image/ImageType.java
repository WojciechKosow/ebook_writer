package com.ebookwriter.SaaS.dto.image;

import com.ebookwriter.SaaS.entity.EbookImageRole;

/**
 * The kind of visual an {@link ImagePlan} calls for. This is a <em>planning</em>
 * concept (how the planner reasons about what a chapter needs); it is finer than
 * the persisted {@link EbookImageRole}, into which every generated image maps as
 * an {@link EbookImageRole#ILLUSTRATION} (its javadoc already covers
 * "illustration, diagram, chart, or figure"). Keeping the finer distinction only
 * on the plan avoids mixing planning data into the stored asset model.
 */
public enum ImageType {

    /** A conceptual or editorial illustration that clarifies an idea. */
    ILLUSTRATION,

    /** A labelled diagram showing structure, flow, or relationships. */
    DIAGRAM,

    /** A chart/graph visualising data or a trend. */
    CHART,

    /** A realistic, photographic-style image of a scene or subject. */
    PHOTO;

    /** Every generated image is stored under the ILLUSTRATION asset role. */
    public EbookImageRole toRole() {
        return EbookImageRole.ILLUSTRATION;
    }

    /** Lenient parse: unknown/blank input falls back to {@link #ILLUSTRATION}. */
    public static ImageType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ILLUSTRATION;
        }
        try {
            return ImageType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ILLUSTRATION;
        }
    }
}
