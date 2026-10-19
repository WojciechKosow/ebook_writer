package com.ebookwriter.SaaS.dto.store;

import java.time.LocalDateTime;
import java.util.List;

/** What the buyer's download page shows before the (counted) download itself. */
public record DownloadInfoResponse(
        String productTitle,
        String storeName,
        String storeSlug,
        String authorName,
        String contactEmail,
        String logoUrl,
        String coverUrl,
        /** Total size of all files. */
        long fileSizeBytes,
        /** Every file the buyer can download; each download counts one. */
        List<ProductFileDTO> files,
        /** Downloads left across all files (each file also shows its own). */
        int downloadsRemaining,
        LocalDateTime expiresAt,
        /** Other ebooks still on sale in the store (empty when it is unpublished). */
        List<PublicStoreResponse.Product> more,
        /** The seller's public profile, for the page frame (null if the store is gone). */
        StoreProfile store,
        /** The store's design (JSON) and the product's page design (JSON) — for the after-purchase page. */
        String themeSettings,
        String pageSettings,
        /** Account perks the guest link leaves out — shown as "comes with a free account". */
        List<ProductFileDTO> perks,
        /** How many times each file can be downloaded with this link. */
        int downloadLimit
) {
}
