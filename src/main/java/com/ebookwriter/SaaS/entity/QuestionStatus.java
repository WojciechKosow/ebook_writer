package com.ebookwriter.SaaS.entity;

/** State of one question Scrivetta asked the author. */
public enum QuestionStatus {
    OPEN,
    ANSWERED,
    /** The author chose not to answer — the gap stays open and must not be filled with invented facts. */
    SKIPPED
}
