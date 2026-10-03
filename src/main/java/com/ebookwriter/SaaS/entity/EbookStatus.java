package com.ebookwriter.SaaS.entity;

/**
 * Lifecycle of an ebook generation. Progress percentages are assigned by the
 * orchestrator: PLANNING ~10%, WRITING 20–80%, EDITING ~85%, PLANNING_IMAGES
 * ~88%, GENERATING_IMAGES 90–95%, RENDERING ~96%, COMPLETED 100%.
 *
 * <p>{@link #DRAFT} is the pre-generation state: the ebook row exists (so assets
 * can be uploaded to it) but no credits are held and generation has not started.
 * A draft moves to {@link #PENDING} when generation is started.
 *
 * <p>{@link #PLANNING_IMAGES} and {@link #GENERATING_IMAGES} cover the AI image
 * pipeline that runs after the manuscript is written and edited. Both are
 * best-effort: a book with no image plan (or with images disabled) passes
 * straight through them to rendering.
 */
public enum EbookStatus {
    DRAFT,
    PENDING,
    PLANNING,
    WRITING,
    /**
     * Paused: the book turned out to need clearly more pages than the user agreed
     * to (or than their credits cover). Nothing continues until the user decides
     * to continue, keep the book within the agreed length, or cancel.
     */
    AWAITING_APPROVAL,
    EDITING,
    PLANNING_IMAGES,
    GENERATING_IMAGES,
    RENDERING,
    COMPLETED,
    FAILED
}
