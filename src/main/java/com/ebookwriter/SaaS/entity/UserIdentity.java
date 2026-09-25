package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An external sign-in method (e.g. Google) linked to a Scrivetta {@link User}.
 *
 * <p>The {@code User} stays the one canonical account — ebooks, credits,
 * subscription and Stripe customer all hang off it. An identity is just another
 * way to authenticate as that user, alongside the password. It is keyed by the
 * provider's stable subject id ({@code sub} for Google), never by email: the
 * email here is only a snapshot of what the provider last reported.
 *
 * <p>Uniqueness: one row per (provider, providerUserId) — the same Google
 * account can never map to two users — and at most one identity per provider
 * per user.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_identities",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_identities_provider_subject",
                        columnNames = {"provider", "provider_user_id"}),
                @UniqueConstraint(name = "uk_user_identities_user_provider",
                        columnNames = {"user_id", "provider"})
        },
        indexes = @Index(name = "idx_user_identities_user", columnList = "user_id"))
public class UserIdentity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Lower-case provider id, e.g. {@code google}. */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** The provider's stable, never-reassigned user id (Google {@code sub}). */
    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    /** Email the provider last reported (informational only). */
    private String email;

    @Column(length = 2048)
    private String pictureUrl;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
