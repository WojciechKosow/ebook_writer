package com.ebookwriter.SaaS.service.store;

import java.util.UUID;

/**
 * Published inside the webhook transaction when an order becomes PAID; the
 * email goes out only after that transaction commits. Carries the raw download
 * token in memory only — it is never persisted.
 */
public record StorePurchaseCompletedEvent(
        UUID orderId,
        String buyerEmail,
        String productTitle,
        String storeName,
        String downloadToken,
        /** Whether the order is already linked to the buyer's Scrivetta account (My Purchases). */
        boolean linkedToAccount
) {
}
