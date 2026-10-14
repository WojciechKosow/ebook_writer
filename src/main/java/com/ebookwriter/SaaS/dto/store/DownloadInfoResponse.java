package com.ebookwriter.SaaS.dto.store;

import java.time.LocalDateTime;

/** What the buyer's download page shows before the (counted) download itself. */
public record DownloadInfoResponse(
        String productTitle,
        String storeName,
        int downloadsRemaining,
        LocalDateTime expiresAt
) {
}
