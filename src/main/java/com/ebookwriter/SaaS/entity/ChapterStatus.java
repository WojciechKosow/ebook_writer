package com.ebookwriter.SaaS.entity;

/**
 * Per-chapter state. Tracked separately so a failure part-way through a book
 * can preserve the chapters already written and resume rather than restart.
 */
public enum ChapterStatus {
    PENDING,
    WRITTEN,
    EDITED,
    FAILED
}
