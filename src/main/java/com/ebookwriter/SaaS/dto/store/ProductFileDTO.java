package com.ebookwriter.SaaS.dto.store;

import com.ebookwriter.SaaS.entity.ProductFileFormat;

import java.time.LocalDateTime;

/**
 * A downloadable file of a product. {@code id} is opaque ("ebook-pdf" for an
 * ebook's own PDF); no storage location is ever included.
 */
public record ProductFileDTO(
        String id,
        /** The download name; null where buyers only see what's included. */
        String filename,
        ProductFileFormat format,
        long sizeBytes,
        int version,
        LocalDateTime updatedAt,
        /** The name buyers see, or null (then: the filename). */
        String label,
        /** What it's for, or null. */
        String note,
        /** Only buyers with a Scrivetta account get it. */
        boolean accountOnly,
        /** Downloads left on a guest link; null where downloads are unlimited or not counted. */
        Integer downloadsLeft,
        /** In My Purchases: replaced with a new version since the purchase. */
        boolean updated
) {
    public ProductFileDTO withDownloadsLeft(Integer left) {
        return new ProductFileDTO(id, filename, format, sizeBytes, version, updatedAt, label, note, accountOnly, left, updated);
    }

    public ProductFileDTO withUpdated(boolean value) {
        return new ProductFileDTO(id, filename, format, sizeBytes, version, updatedAt, label, note, accountOnly, downloadsLeft, value);
    }

    /** For pages before purchase: what's included, without download names. */
    public ProductFileDTO withoutFilename() {
        return new ProductFileDTO(id, null, format, sizeBytes, version, updatedAt, label, note, accountOnly, downloadsLeft, updated);
    }
}
