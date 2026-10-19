package com.ebookwriter.SaaS.dto.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One paid purchase in the buyer's My Purchases, with the files they can download now. */
public record PurchaseResponse(
        UUID orderId,
        UUID productId,
        String productTitle,
        String storeName,
        String storeSlug,
        String authorName,
        String contactEmail,
        String coverUrl,
        long amount,
        String currency,
        LocalDateTime purchasedAt,
        /** Whether the product's page is still on sale (buyers keep their files either way). */
        boolean onSale,
        /** Files (account perks included); {@code updated} marks one replaced since the purchase. */
        List<ProductFileDTO> files,
        /** When the latest of those updates happened, or null. */
        LocalDateTime updatedAt
) {
}
