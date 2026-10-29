package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user's subscription state, mirrored from Stripe via webhooks. There is a
 * single plan (PRO), so this tracks the Stripe subscription id, its status,
 * whether it's set to cancel at period end, the current period end and the
 * Price it renews on (which tells a legacy-priced subscriber apart).
 *
 * <p>This server-side record — never a client flag — is what PRO entitlements
 * (the Marketplace storefront, the kept asset library) are checked against.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "user_subscriptions", indexes = {
        @Index(columnList = "stripeSubscriptionId")
})
public class UserSubscription {

    @Id
    private UUID userId;

    private String stripeSubscriptionId;

    /** Raw Stripe status: active, past_due, canceled, incomplete, … */
    private String status;

    private boolean cancelAtPeriodEnd;

    private LocalDateTime currentPeriodEnd;

    /** The Stripe Price the subscription renews on (current PRO or a legacy price). */
    private String stripePriceId;

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
