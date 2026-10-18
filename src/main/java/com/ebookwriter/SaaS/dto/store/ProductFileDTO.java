package com.ebookwriter.SaaS.dto.store;

import com.ebookwriter.SaaS.entity.ProductFileFormat;

import java.time.LocalDateTime;

/**
 * A downloadable file of a product. {@code id} is opaque ("ebook-pdf" for an
 * ebook's own PDF); no storage location is ever included.
 */
public record ProductFileDTO(
        String id,
        String filename,
        ProductFileFormat format,
        long sizeBytes,
        int version,
        LocalDateTime updatedAt
) {
}
