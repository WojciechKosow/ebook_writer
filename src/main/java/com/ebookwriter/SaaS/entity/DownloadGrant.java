package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The buyer's right to download a paid order's file. The token itself is only
 * ever emailed; we store its SHA-256 hash. Limited by time and by count, and
 * revoked on a full refund.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "download_grant", indexes = {
        @Index(columnList = "tokenHash", unique = true),
        @Index(columnList = "orderId", unique = true)
})
public class DownloadGrant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID orderId;

    /** Hex SHA-256 of the token. */
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private int downloadsUsed;

    private int maxDownloads;

    /**
     * Downloads used per file, as {@code fileId=count} pairs separated by commas.
     * Each file may be downloaded {@code DownloadGrantService.MAX_DOWNLOADS} times.
     */
    @Column(columnDefinition = "TEXT")
    private String fileDownloads;

    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
