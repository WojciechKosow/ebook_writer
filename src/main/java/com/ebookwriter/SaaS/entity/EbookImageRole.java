package com.ebookwriter.SaaS.entity;

/**
 * The <em>kind</em> of an ebook asset — what it depicts, independent of whether
 * or where it is used (that is {@link EbookImagePlacement}). Roles are never
 * forced on the user at upload (everything starts {@link #GENERAL}); they are
 * assigned automatically during asset analysis or adjusted later in the editor,
 * and help the AI decide sensible placements.
 */
public enum EbookImageRole {

    /** Unclassified / general-purpose image (the default at upload). */
    GENERAL,

    /** A brand or company logo. */
    LOGO,

    /** A portrait of the author or a person. */
    AUTHOR,

    /** A product shot. */
    PRODUCT,

    /** Suited to the book cover. */
    COVER,

    /** A chapter illustration, diagram, chart, or figure. */
    ILLUSTRATION
}
