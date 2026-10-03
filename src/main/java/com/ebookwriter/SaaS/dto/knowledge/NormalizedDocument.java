package com.ebookwriter.SaaS.dto.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One piece of normalised text extracted from an author's source, ready for
 * analysis. An archive (ZIP / RAR) yields one per useful file (plus a {@link Kind#STRUCTURE}
 * listing of the archive); a PDF/DOCX/TXT/MD upload or the notes yield one.
 *
 * @param path        where it came from: the path inside an archive, the
 *                    uploaded filename, or {@code user-notes}
 * @param kind        coarse role of the document (drives analysis priority)
 * @param extension   lower-case file extension, or empty
 * @param language    programming/markup language for code & config, else null
 * @param chars       length of {@code content}
 * @param truncated   true when the original was longer than the per-document limit
 * @param contentHash hash of the whitespace-normalised content (duplicate detection)
 * @param content     the normalised text
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NormalizedDocument(String path, Kind kind, String extension, String language,
                                 int chars, boolean truncated, String contentHash, String content) {

    public enum Kind {
        /** The author's own notes — first-class, analysed first. */
        NOTES,
        /** File/folder listing of an archive. */
        STRUCTURE,
        /** Prose: README, docs, PDFs, DOCX, plain text. */
        DOCUMENT,
        /** Build files and manifests (pom.xml, package.json, Dockerfile, …). */
        BUILD,
        /** Configuration (application.yml, *.properties, …). */
        CONFIG,
        /** Source code. */
        CODE,
        /** Tests (lower priority than the code they test). */
        TEST,
        /** Structured data / anything else textual. */
        DATA
    }
}
