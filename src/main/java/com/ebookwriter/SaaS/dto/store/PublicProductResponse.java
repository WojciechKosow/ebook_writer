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
        List<PublicStoreResponse.Product> more,
        /** The store's design (JSON) — the product page uses the same style. */
        String themeSettings,
        /** The product page design (JSON), null for defaults. */
        String pageSettings,
        /** Chapters buyers can read for free (see the preview endpoint). */
        List<Chapter> sampleChapters,
        /** What's included: every file a buyer gets (no download names), account perks marked. */
        List<ProductFileDTO> included,
        /** The guest download rule: each file this many times… */
        int downloadLimit,
        /** …within this many days. */
        int downloadDays
) {
    public record Chapter(int number, String title) {
    }
}
