package com.ebookwriter.SaaS.entity;

/**
 * Lifecycle of an ebook generation. Progress percentages are assigned by the
 * orchestrator: PLANNING ~10%, WRITING 20–80%, EDITING ~90%, RENDERING ~95%,
 * COMPLETED 100%.
 */
public enum EbookStatus {
    PENDING,
    PLANNING,
    WRITING,
    EDITING,
    RENDERING,
    COMPLETED,
    FAILED
}
