package com.ebookwriter.SaaS.dto.store;

/**
 * The seller as buyers see them on every store page. Image URLs are relative
 * API paths (null when not uploaded).
 */
public record StoreProfile(
        String slug,
        String name,
        String description,
        String headline,
        String authorName,
        String contactEmail,
        String instagramUrl,
        String tiktokUrl,
        String websiteUrl,
        String logoUrl,
        String photoUrl,
        /** False with an honest reason when the store can't take payments right now. */
        boolean purchasable,
        String unavailableReason
) {
}
