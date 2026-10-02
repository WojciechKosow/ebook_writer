package com.ebookwriter.SaaS.entity;

/**
 * Lifecycle of a book's knowledge ingestion ("Tell Scrivetta what you know"),
 * tracked on {@link BookKnowledge} — separate from {@link EbookStatus}, which is
 * the generation lifecycle. A book stays a {@link EbookStatus#DRAFT} throughout.
 *
 * <pre>
 *   CREATED → MATERIALS_UPLOADING → PROCESSING → ANALYZING → KNOWLEDGE_READY → READY_FOR_BLUEPRINT
 *                                        └──────────┴──→ FAILED (retryable)
 * </pre>
 * <ul>
 *   <li>{@link #CREATED} — no materials yet (also what a book without a
 *       knowledge row reports);</li>
 *   <li>{@link #MATERIALS_UPLOADING} — materials added, not (re)processed yet;
 *       adding or removing a source after processing returns here;</li>
 *   <li>{@link #PROCESSING} — normalising, de-duplicating and batching;</li>
 *   <li>{@link #ANALYZING} — OpenAI is reading the batches;</li>
 *   <li>{@link #KNOWLEDGE_READY} — structured BookKnowledge is stored;</li>
 *   <li>{@link #READY_FOR_BLUEPRINT} — the author reviewed it and continued;
 *       the next (blueprint) stage starts from here.</li>
 * </ul>
 */
public enum KnowledgeStatus {
    CREATED,
    MATERIALS_UPLOADING,
    PROCESSING,
    ANALYZING,
    KNOWLEDGE_READY,
    READY_FOR_BLUEPRINT,
    FAILED;

    /** True when stored BookKnowledge exists and matches the current materials. */
    public boolean hasKnowledge() {
        return this == KNOWLEDGE_READY || this == READY_FOR_BLUEPRINT;
    }

    /** True while a processing run is in flight. */
    public boolean isRunning() {
        return this == PROCESSING || this == ANALYZING;
    }
}
