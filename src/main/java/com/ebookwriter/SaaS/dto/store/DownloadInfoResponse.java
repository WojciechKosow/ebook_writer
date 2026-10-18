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
        int downloadsRemaining,
        LocalDateTime expiresAt,
        /** Other ebooks still on sale in the store (empty when it is unpublished). */
        List<PublicStoreResponse.Product> more
) {
}
