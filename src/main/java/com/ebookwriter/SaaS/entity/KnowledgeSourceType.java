package com.ebookwriter.SaaS.entity;

import java.util.Locale;

/** What kind of material the author provided. */
public enum KnowledgeSourceType {
    ZIP,
    PDF,
    DOCX,
    TXT,
    MD,
    /** Pasted text / notes — a first-class source, however rough. */
    NOTES;

    /** Map an uploaded filename to its type, or null when the format is unsupported. */
    public static KnowledgeSourceType fromFilename(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase(Locale.ROOT).strip();
        if (lower.endsWith(".zip")) return ZIP;
        if (lower.endsWith(".pdf")) return PDF;
        if (lower.endsWith(".docx")) return DOCX;
        if (lower.endsWith(".txt")) return TXT;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return MD;
        return null;
    }
}
