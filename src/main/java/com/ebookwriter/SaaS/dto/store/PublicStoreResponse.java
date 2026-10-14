package com.ebookwriter.SaaS.dto.store;

import java.util.List;
import java.util.UUID;

/**
 * A published store as buyers see it. {@code purchasable} is false with an
 * honest {@code unavailableReason} when the store can't take payments right now;
 * checkout re-checks everything server-side regardless.
 */
public record PublicStoreResponse(
        String slug,
        String name,
        String description,
        boolean purchasable,
        String unavailableReason,
        List<Product> products
) {
    public record Product(UUID id, String title, String description, long priceCents, String currency) {
    }
}
