package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A digital product for sale in a {@link Store}: either a finished Scrivetta
 * ebook of the store owner (its current PDF is delivered) or a standalone
 * product with no ebook, or both — plus any number of uploaded
 * {@link ProductFile}s (PDF, EPUB, ZIP). The price lives here, in our database —
 * Checkout is built from it, the client never sends it.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "store_products",
        uniqueConstraints = @UniqueConstraint(columnNames = {"storeId", "ebookId"}),
        indexes = @Index(columnList = "storeId"))
public class StoreProduct {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID storeId;

    /** The Scrivetta ebook whose PDF is delivered, or null for a standalone product (uploaded files only). */
    private UUID ebookId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** "What you'll be able to do" — one point per line. */
    @Column(columnDefinition = "TEXT")
    private String highlights;

    /** Position in the store (ascending); the first active product is featured. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private int sortOrder;

    /** Price in the smallest currency unit (cents). */
    @Column(nullable = false)
    private long priceCents;

    /** ISO currency code, lowercase as Stripe expects (e.g. "usd"). */
    @Column(nullable = false, length = 3)
    private String currency;

    private boolean active = true;

    /**
     * Private R2 key of the author's own cover/thumbnail (separate from the
     * downloadable files). Null = the ebook's first page, or no cover.
     */
    private String coverKey;
    private String coverContentType;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
