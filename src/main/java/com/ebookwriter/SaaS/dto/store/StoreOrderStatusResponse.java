package com.ebookwriter.SaaS.dto.store;

import com.ebookwriter.SaaS.entity.StoreOrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the buyer's success page polls. Without the order's Checkout Session id
 * it carries no buyer data; with it (the buyer's own browser, back from Stripe)
 * and once paid, it also carries the files to download right away.
 */
public record StoreOrderStatusResponse(
        UUID orderId,
        StoreOrderStatus status,
        String productTitle,
        String storeSlug,
        String storeName,
        /** The product's page design (JSON) — the success page shows the author's thank-you. Public anyway. */
        String pageSettings,
        UUID productId,
        String coverUrl,
        /** The rest needs the session id; null otherwise. Amount paid, in cents. */
        Long amount,
        /** Where the download link was emailed. */
        String buyerEmail,
        /** The files, each with its downloads left (null until paid). */
        List<ProductFileDTO> files,
        /** Account perks the guest doesn't get yet. */
        List<ProductFileDTO> perks,
        LocalDateTime expiresAt,
        /** The order is already linked to a Scrivetta account (it's in their My Purchases). */
        Boolean inLibrary,
        int downloadLimit
) {
}
