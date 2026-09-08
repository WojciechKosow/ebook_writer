package com.ebookwriter.SaaS.entity;

/**
 * What an {@link EbookImage} is used for in the rendered book.
 */
public enum EbookImageRole {

    /** The book's cover image, shown on the cover page. At most one per book. */
    COVER,

    /**
     * An image the author places inside a chapter by referencing it in the
     * chapter Markdown (see {@code EbookImage.markdownRef()}).
     */
    INLINE
}
