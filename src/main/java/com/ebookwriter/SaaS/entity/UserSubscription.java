package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user's subscription state, mirrored from Stripe via webhooks. There is a
 * single plan in V0.1, so this just tracks the Stripe subscription id, its
 * status, whether it's set to cancel at period end, and the current period end.
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
