package com.ebookwriter.SaaS.service.library;

import com.ebookwriter.SaaS.dto.AssetLibraryResponse;
import com.ebookwriter.SaaS.dto.LibraryAssetDTO;
import com.ebookwriter.SaaS.entity.LibraryAsset;
import com.ebookwriter.SaaS.repository.LibraryAssetRepository;
import com.ebookwriter.SaaS.service.ebook.EbookImageService;
import com.ebookwriter.SaaS.service.payment.SubscriptionService;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The user's asset library: one copy of every image they upload, reusable in any
 * place that takes an image (book assets, store logo and photo) without uploading
 * it again. Uploading a file that is already in the library returns the existing
 * asset.
 *
 * <p>With PRO the library is kept. Without it, an asset is purged
 * {@code retentionHours} after it was last uploaded or used; upgrading before
 * then keeps it. Places that use a library asset get their own copy of the
 * bytes, so a purge or a delete here never breaks them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetLibraryService {

    private final LibraryAssetRepository repository;
    private final R2StorageService storage;
    private final SubscriptionService subscriptionService;

    @Value("${library.free-retention-hours:24}")
    private int retentionHours;

    /** The user's library, most recently used first. Upgrading to PRO keeps pending assets. */
    @Transactional
    public AssetLibraryResponse list(UUID userId) {
        boolean pro = subscriptionService.isPro(userId);
        if (pro) {
            keepAll(userId);
        }
        List<LibraryAssetDTO> assets = repository.findByUserIdOrderByLastUsedAtDesc(userId).stream()
                .map(LibraryAssetDTO::from)
                .toList();
        return new AssetLibraryResponse(pro, retentionHours, assets);
    }

    /** Upload straight into the library (any image a book accepts). */
    @Transactional
    public LibraryAssetDTO upload(UUID userId, MultipartFile file) {
        byte[] bytes = EbookImageService.readUpload(file);
        String contentType = EbookImageService.validateImage(bytes);
        int[] dimensions = EbookImageService.readDimensions(bytes);
        return LibraryAssetDTO.from(remember(userId, bytes, contentType,
                EbookImageService.sanitizeFilename(file.getOriginalFilename()), dimensions[0], dimensions[1]));
    }

    /**
     * Add already-validated image bytes to the user's library, or — when the same
     * file is already there — refresh and return that asset.
     */
    @Transactional
    public LibraryAsset remember(UUID userId, byte[] bytes, String contentType, String filename,
                                 int width, int height) {
        String hash = sha256(bytes);
        LocalDateTime expiresAt = expiryFor(userId);
        LibraryAsset existing = repository.findFirstByUserIdAndSha256(userId, hash).orElse(null);
        if (existing != null) {
            existing.setLastUsedAt(LocalDateTime.now());
            existing.setExpiresAt(expiresAt);
            return repository.save(existing);
        }
        String key = "library/" + userId + "/" + UUID.randomUUID() + "." + extension(contentType);
        storage.upload(key, bytes, contentType);
        LibraryAsset asset = repository.save(LibraryAsset.builder()
                .userId(userId)
                .storageKey(key)
                .sha256(hash)
                .contentType(contentType)
                .originalFilename(filename)
                .sizeBytes(bytes.length)
                .width(width)
                .height(height)
                .expiresAt(expiresAt)
                .build());
        log.info("[Library] Saved asset {} for user {} ({}, {} KB)", asset.getId(), userId, contentType,
                bytes.length / 1024);
        return asset;
    }

    /**
     * Load a library asset to use it somewhere. Counts as a use: the asset moves
     * to the top of the library and, without PRO, its retention restarts.
     */
    @Transactional
    public Loaded use(UUID userId, UUID assetId) {
        LibraryAsset asset = require(userId, assetId);
        byte[] bytes = storage.download(asset.getStorageKey())
                .orElseThrow(() -> new IllegalStateException("This image is no longer in your library"));
        asset.setLastUsedAt(LocalDateTime.now());
        asset.setExpiresAt(expiryFor(userId));
        repository.save(asset);
        return new Loaded(asset, bytes);
    }

    @Transactional(readOnly = true)
    public EbookImageService.RawImage raw(UUID userId, UUID assetId) {
        LibraryAsset asset = require(userId, assetId);
        byte[] bytes = storage.download(asset.getStorageKey())
                .orElseThrow(() -> new IllegalStateException("Image bytes are missing from storage"));
        return new EbookImageService.RawImage(bytes, asset.getContentType(), asset.getOriginalFilename());
    }

    /** Remove an asset from the library. Places already using it keep their copy. */
    @Transactional
    public void delete(UUID userId, UUID assetId) {
        LibraryAsset asset = require(userId, assetId);
        repository.delete(asset);
        deleteBytesQuietly(asset.getStorageKey());
    }

    /**
     * Purge expired assets of users without PRO. An owner who has upgraded since
     * keeps theirs.
     */
    @Scheduled(initialDelayString = "${library.purge-initial-delay-ms:600000}",
            fixedDelayString = "${library.purge-interval-ms:3600000}")
    @Transactional
    public void purgeExpired() {
        List<LibraryAsset> expired = repository.findByExpiresAtBefore(LocalDateTime.now());
        int purged = 0;
        Map<UUID, Boolean> pro = new HashMap<>();
        for (LibraryAsset asset : expired) {
            if (pro.computeIfAbsent(asset.getUserId(), subscriptionService::isPro)) {
                asset.setExpiresAt(null);
                repository.save(asset);
                continue;
            }
            repository.delete(asset);
            deleteBytesQuietly(asset.getStorageKey());
            purged++;
        }
        if (purged > 0) {
            log.info("[Library] Purged {} expired asset(s)", purged);
        }
    }

    // ---- internals ----------------------------------------------------------

    private void keepAll(UUID userId) {
        for (LibraryAsset asset : repository.findByUserIdAndExpiresAtIsNotNull(userId)) {
            asset.setExpiresAt(null);
            repository.save(asset);
        }
    }

    private LocalDateTime expiryFor(UUID userId) {
        return subscriptionService.isPro(userId) ? null : LocalDateTime.now().plusHours(retentionHours);
    }

    private LibraryAsset require(UUID userId, UUID assetId) {
        return repository.findByIdAndUserId(assetId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found in your library"));
    }

    private void deleteBytesQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("[Library] Could not delete {}: {}", key, e.getMessage());
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/svg+xml" -> "svg";
            default -> contentType.substring(contentType.indexOf('/') + 1);
        };
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A library asset with its bytes, ready to be copied into a book or store. */
    public record Loaded(LibraryAsset asset, byte[] bytes) {
    }
}
