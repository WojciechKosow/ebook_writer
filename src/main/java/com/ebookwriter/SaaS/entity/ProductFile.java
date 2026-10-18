package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One downloadable file of a {@link StoreProduct} (PDF, EPUB or ZIP), stored
 * unmodified in the private R2 bucket. Rows are never deleted: replacing a file
 * creates a new row ({@link #version} + 1, {@link #replacesId} pointing back) and
 * marks the old one removed, so the history stays available for support. Buyers
 * always get the product's current files ({@code removedAt IS NULL}).
 *
 * <p>{@link #storageKey} is chosen by the server and never leaves the backend.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "store_product_files", indexes = {
        @Index(columnList = "productId"),
        @Index(columnList = "storeId")
})
public class ProductFile {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private UUID storeId;

    /** The author's filename, sanitised (no path, no control characters). Used as the download name. */
    @Column(nullable = false, length = 255)
    private String originalFilename;

    /** Served content type — derived from {@link #format}, not from the upload. */
    @Column(nullable = false, length = 100)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ProductFileFormat format;

    @Column(nullable = false)
    private long sizeBytes;

    /** Hex SHA-256 of the stored bytes (integrity / troubleshooting). */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String storageKey;

    /** 1 for a new file; a replacement is its predecessor's version + 1. */
    @Column(nullable = false)
    private int version = 1;

    /** The file this one replaced, or null. */
    private UUID replacesId;

    @Column(nullable = false)
    private int sortOrder;

    /** The author who uploaded it. */
    @Column(nullable = false)
    private UUID uploadedBy;

    private LocalDateTime createdAt;

    /** Set when the file was replaced or removed; null = current. */
    private LocalDateTime removedAt;

    /** REPLACED or REMOVED. */
    @Column(length = 16)
    private String removedReason;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isCurrent() {
        return removedAt == null;
    }
}
