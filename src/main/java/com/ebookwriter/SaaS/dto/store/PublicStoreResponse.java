package com.ebookwriter.SaaS.dto.store;

import java.util.List;
import java.util.UUID;

/**
 * A published store as buyers see it. Checkout re-checks everything
 * server-side regardless of {@code store.purchasable}.
 */
public record PublicStoreResponse(
        StoreProfile store,
        List<Product> products,
        /** The store page design (JSON), null for the default (Scrivetta) style. */
        String themeSettings,
        /** Chapters of the first (featured) ebook, for designs that show its contents. */
        List<PublicProductResponse.Chapter> featuredChapters
) {
    public record Product(UUID id, String title, String subtitle, String description,
                          long priceCents, String currency, int pageCount, String language,
                          String coverUrl) {
    }
}
