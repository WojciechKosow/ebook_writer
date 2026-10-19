package com.ebookwriter.SaaS.dto.store;

import java.time.LocalDateTime;
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
        List<StoreProductDTO> products,
        /** The store page design (JSON), null for the default (Scrivetta) style. */
        String themeSettings,
        /** Chapters of the first active ebook, so the design editor previews what buyers see. */
        List<PublicProductResponse.Chapter> featuredChapters,
        /**
         * What "Publish changes" would apply. Every other field shows the draft
         * (what the author is editing); these say how it differs from the live store.
         */
        List<PendingChange> changes,
        /** When the draft was last saved, or null. */
        LocalDateTime draftSavedAt
) {
}
