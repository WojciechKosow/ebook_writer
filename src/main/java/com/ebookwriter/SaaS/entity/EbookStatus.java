package com.ebookwriter.SaaS.entity;

/**
 * Lifecycle of an ebook generation. Progress percentages are assigned by the
 * orchestrator: PLANNING ~10%, WRITING 20–80%, EDITING ~90%, RENDERING ~95%,
 * COMPLETED 100%.
 *
 * <p>{@link #DRAFT} is the pre-generation state: the ebook row exists (so assets
 * can be uploaded to it) but no credits are held and generation has not started.
 * A draft moves to {@link #PENDING} when generation is started.
 */
public enum EbookStatus {
    DRAFT,
    PENDING,
    PLANNING,
    WRITING,
    EDITING,
    RENDERING,
    COMPLETED,
    FAILED
}
