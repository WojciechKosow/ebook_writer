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

    /** The "about the author" text shown on the store. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Hero line: what readers get ("Bake bread you're proud of…"). */
    @Column(length = 160)
    private String headline;

    /** The author's name as buyers see it ("by Jane Morrow"). */
    @Column(length = 80)
    private String authorName;

    /** Public contact address for buyers (questions, refunds). */
    @Column(length = 254)
    private String contactEmail;

    @Column(length = 500)
    private String instagramUrl;

    @Column(length = 500)
    private String tiktokUrl;

    @Column(length = 500)
    private String websiteUrl;

    /** Private R2 keys of the store's logo / author photo (served via the public API). */
    private String logoKey;
    private String logoContentType;
    private String photoKey;
    private String photoContentType;

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

    /**
     * The store page's look, as the author designed it: a JSON document
     * ({@code {"v":1,"active":"code","themes":{...}}}) that only the frontend
     * interprets. Null = the classic look. Validated by {@link com.ebookwriter.SaaS.service.store.StoreThemeValidator}.
     */
    @Column(columnDefinition = "TEXT")
    private String themeSettings;

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
