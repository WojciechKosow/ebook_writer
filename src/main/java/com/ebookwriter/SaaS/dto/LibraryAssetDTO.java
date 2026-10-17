package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.LibraryAsset;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An asset in the user's library. The bucket is private, so the bytes are served
 * through the authenticated {@code /api/library/{id}/raw} endpoint. A null
 * {@link #expiresAt} means the asset is kept.
 */
public record LibraryAssetDTO(
        UUID id,
        String contentType,
        String originalFilename,
        long sizeBytes,
        int width,
        int height,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        String rawUrl
) {
    public static LibraryAssetDTO from(LibraryAsset asset) {
        return new LibraryAssetDTO(
                asset.getId(),
                asset.getContentType(),
                asset.getOriginalFilename(),
                asset.getSizeBytes(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getCreatedAt(),
                asset.getExpiresAt(),
                "/api/library/" + asset.getId() + "/raw");
    }
}
