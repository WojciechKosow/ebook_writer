package com.ebookwriter.SaaS.entity;

/**
 * Per-chapter state. Tracked separately so a failure part-way through a book
 * can preserve the chapters already written and resume rather than restart.
 */
public enum ChapterStatus {
    PENDING,
    WRITTEN,
    EDITED,
    FAILED,
    /**
     * Planned but deliberately left out of this book because the user's credits
     * could not cover the full plan: the book was wound down to a natural ending
     * instead of being cut off mid-way. The outline entry is kept (no content) so
     * a future "continue" feature can pick it up. Never rendered.
     */
    DEFERRED
}
