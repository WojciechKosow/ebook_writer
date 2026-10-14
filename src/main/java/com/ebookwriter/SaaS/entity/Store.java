package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An author's creator store, served publicly at {@code /store/{slug}} once
 * published. One store per author in the MVP.
 *
 * <p>The author is the seller: buyers pay the author's own Stripe connected
 * account ({@link #stripeAccountId}) directly. The Stripe status fields are a
 * server-side mirror of that account, refreshed only by fetching the account
 * from Stripe (on return from onboarding and on {@code account.updated}) — never
 * set just because the author came back from Stripe.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "stores", indexes = {
        @Index(columnList = "slug", unique = true),
        @Index(columnList = "ownerId", unique = true),
        @Index(columnList = "stripeAccountId", unique = true)
})
public class Store {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID ownerId;

    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private boolean published;

    /** The author's Stripe connected account (acct_...), created on "Connect Stripe". */
    @Column(unique = true)
    private String stripeAccountId;

    private boolean chargesEnabled;

    private boolean detailsSubmitted;

    /** Comma-separated {@code requirements.currently_due}, as last fetched from Stripe. */
    @Column(columnDefinition = "TEXT")
    private String requirementsCurrentlyDue;

    private LocalDateTime stripeSyncedAt;

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
