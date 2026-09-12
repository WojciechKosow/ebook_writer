package com.ebookwriter.SaaS.entity;

/**
 * Where an asset is actually used in the rendered book. Distinct from
 * {@link EbookImageRole} (what the asset <em>is</em>): an asset can be a LOGO by
 * role yet UNUSED by placement.
 *
 * <p>For {@link #CHAPTER} placements the position <em>within</em> the chapter is
 * carried by the {@code ebook-image:<id>} reference in the chapter's Markdown —
 * that Markdown is the source of truth for inline placement, and the stored
 * placement is kept in sync with it. {@link #COVER} lives outside chapter text,
 * so it is represented only here.
 */
public enum EbookImagePlacement {

    /** In the asset library but not placed anywhere in the book. */
    UNUSED,

    /** Used as the book's cover image (at most one asset). */
    COVER,

    /** Placed inside a chapter (see {@code EbookImage.chapter}). */
    CHAPTER
}
