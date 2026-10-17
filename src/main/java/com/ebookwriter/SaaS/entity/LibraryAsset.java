package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An image in a user's asset library: every image they upload (book assets, store
 * logo and photo) lands here once, so it can be reused in any other place that
 * takes an image instead of being uploaded again. Identical files are kept once
 * per user ({@link #sha256}).
 *
 * <p>PRO users keep their library ({@link #expiresAt} is null). Without PRO an
 * asset is kept for a limited time and then purged; upgrading before then keeps
 * it. Using a library asset somewhere copies its bytes, so a purge never breaks a
 * book or a store.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "library_assets", indexes = {
        @Index(columnList = "userId"),
        @Index(columnList = "userId, sha256"),
        @Index(columnList = "expiresAt")
})
public class LibraryAsset {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    /** Object key in the R2 bucket ({@code library/<userId>/<uuid>.<ext>}). */
    @Column(nullable = false, columnDefinition = "text")
    private String storageKey;

    /** Hex SHA-256 of the bytes — the same file is stored once per user. */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private String contentType;

    private String originalFilename;

    private long sizeBytes;

    /** Pixel dimensions, when they could be read (else 0). */
    private int width;
    private int height;

    /** When a non-PRO asset is purged; null = kept. */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    /** Last time the asset was uploaded again or used — the library lists recent first. */
    private LocalDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.lastUsedAt == null) {
            this.lastUsedAt = this.createdAt;
        }
    }
}
