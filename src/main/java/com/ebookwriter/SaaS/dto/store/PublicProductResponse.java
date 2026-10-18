package com.ebookwriter.SaaS.dto.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One ebook's own page in a published store — where buyers decide. */
public record PublicProductResponse(
        StoreProfile store,
        UUID id,
        String title,
        String subtitle,
        String description,
        List<String> highlights,
        long priceCents,
        String currency,
        int pageCount,
        String language,
        LocalDateTime updatedAt,
        String coverUrl,
        List<Chapter> chapters,
        /** The formats buyers get (e.g. PDF, EPUB, ZIP), in delivery order, without duplicates. */
        List<String> formats,
        /** Other active ebooks of the same store. */
        List<PublicStoreResponse.Product> more
) {
    public record Chapter(int number, String title) {
    }
}
