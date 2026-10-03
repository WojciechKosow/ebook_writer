package com.ebookwriter.SaaS.service.knowledge.archive;

import com.ebookwriter.SaaS.entity.KnowledgeSourceType;

/**
 * One archive format (ZIP, RAR, …). A reader only knows how to open its format
 * and list/read entries; everything else — path safety, limits, classification,
 * extraction into documents — is shared in
 * {@link com.ebookwriter.SaaS.service.knowledge.ArchiveKnowledgeExtractor}, so
 * every format is treated exactly the same.
 */
public interface ArchiveReader {

    /** The upload type this reader handles. */
    KnowledgeSourceType type();

    /** Short name used in messages ("ZIP", "RAR"). */
    default String formatName() {
        return type().name();
    }

    /** Whether the bytes start with this format's signature. */
    boolean matches(byte[] bytes);

    /** Open the archive held in {@code bytes}; the caller closes it. */
    OpenedArchive open(byte[] bytes) throws ArchiveReadException;

    static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes == null || bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }
}
