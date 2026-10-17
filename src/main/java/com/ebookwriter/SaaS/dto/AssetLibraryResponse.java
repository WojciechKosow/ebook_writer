package com.ebookwriter.SaaS.dto;

import java.util.List;

/**
 * The user's asset library. {@code kept} is true with PRO (assets stay);
 * otherwise each asset is purged {@code retentionHours} after it was last
 * uploaded or used.
 */
public record AssetLibraryResponse(
        boolean kept,
        int retentionHours,
        List<LibraryAssetDTO> assets
) {
}
