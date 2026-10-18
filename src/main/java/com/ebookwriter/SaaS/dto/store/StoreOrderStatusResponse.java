package com.ebookwriter.SaaS.dto.store;

import com.ebookwriter.SaaS.entity.StoreOrderStatus;

import java.util.UUID;

/** What the buyer's success page polls. Carries no buyer data. */
public record StoreOrderStatusResponse(
        UUID orderId,
        StoreOrderStatus status,
        String productTitle,
        String storeSlug,
        String storeName,
        /** The product's page design (JSON) — the success page shows the author's thank-you. Public anyway. */
        String pageSettings,
        UUID productId,
        String coverUrl
) {
}
