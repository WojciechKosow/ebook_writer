package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single purchase attempt. Created before redirecting to Stripe Checkout and
 * used as the idempotent fulfilment record: the webhook flips it to PAID exactly
 * once and grants credits, and the frontend polls it to learn the real outcome
 * (never trusting the browser redirect).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentPurpose purpose;

    /** Set when purpose == CREDIT_PACK. */
    @Enumerated(EnumType.STRING)
    private CreditPack creditPack;

    /** Credits granted on fulfilment. */
    private int creditsGranted;

    private int amountCents;
    private String currency;

    @Column(unique = true)
    private String stripeCheckoutSessionId;

    private String stripePaymentIntentId;

    /** Set when purpose == SUBSCRIPTION. */
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentOrderStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime fulfilledAt;

    @Version
    private Long version;

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
