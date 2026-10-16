package com.ebookwriter.SaaS.dto.store;

import java.util.List;
import java.util.UUID;

/** The author's own store, as shown in the store dashboard. */
public record StoreDashboardResponse(
        UUID id,
        String slug,
        String name,
        String description,
        String headline,
        String authorName,
        String contactEmail,
        String instagramUrl,
        String tiktokUrl,
        String websiteUrl,
        /** Relative API paths, or null when not uploaded. */
        String logoUrl,
        String photoUrl,
        boolean published,
        /** Whether the author has Scrivetta PRO (required to publish and to sell). */
        boolean pro,
        StripeStatusDTO stripe,
        List<StoreProductDTO> products
) {
}
