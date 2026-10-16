package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An ebook for sale in a {@link Store}. In the MVP a product is always a
 * finished Scrivetta ebook of the store owner; the file delivered is that
 * ebook's current PDF. The price lives here, in our database — Checkout is
 * built from it, the client never sends it.
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

    @Column(nullable = false)
    private UUID ebookId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Price in the smallest currency unit (cents). */
    /** "What you'll be able to do" — one point per line. */
    @Column(columnDefinition = "TEXT")
    private String highlights;

    /** Position in the store (ascending); the first active product is featured. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private int sortOrder;

    @Column(nullable = false)
    private long priceCents;

    /** ISO currency code, lowercase as Stripe expects (e.g. "usd"). */
    @Column(nullable = false, length = 3)
    private String currency;

    private boolean active = true;

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
