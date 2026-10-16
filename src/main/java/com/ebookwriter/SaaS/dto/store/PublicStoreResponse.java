package com.ebookwriter.SaaS.dto.store;

import java.util.List;
import java.util.UUID;

/**
 * A published store as buyers see it. Checkout re-checks everything
 * server-side regardless of {@code store.purchasable}.
 */
public record PublicStoreResponse(
        StoreProfile store,
        List<Product> products
) {
    public record Product(UUID id, String title, String subtitle, String description,
                          long priceCents, String currency, int pageCount, String language,
                          String coverUrl) {
    }
}
