package com.ebookwriter.SaaS.entity;

/** Outcome of extracting one uploaded source. */
public enum KnowledgeSourceStatus {
    /** Everything useful was extracted. */
    EXTRACTED,
    /** Some content was extracted; some files were skipped or failed (see skipped list). */
    PARTIAL,
    /** Nothing usable could be extracted (corrupt file, scanned PDF, …). Ignored by processing. */
    FAILED
}
