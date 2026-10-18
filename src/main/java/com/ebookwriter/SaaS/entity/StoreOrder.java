package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One buyer purchase in a creator store. Created PENDING when the Checkout
 * Session is created; only the signed Connect webhook marks it PAID, EXPIRED or
 * REFUNDED. Amount, currency, product and the seller's connected account are
 * copied from our database at checkout time — never taken from the client.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "store_order", indexes = {
        @Index(columnList = "checkoutSessionId", unique = true),
        @Index(columnList = "paymentIntentId"),
        @Index(columnList = "storeId"),
        @Index(columnList = "buyerUserId")
})
public class StoreOrder {

    @Id
    @GeneratedValue
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private UUID productId;

    /** The seller's connected account the Checkout Session was created on. */
    @Column(nullable = false)
    private String stripeAccountId;

    @Column(unique = true)
    private String checkoutSessionId;

    private String paymentIntentId;

    private String buyerEmail;

    /**
     * The buyer's Scrivetta account, once known: set at checkout when the buyer
     * was signed in, by the webhook when a verified account has the buyer's
     * email, or when a verified account with that email first opens My Purchases.
     * Access still requires {@link StoreOrderStatus#PAID}.
     */
    private UUID buyerUserId;

    /** Amount in cents. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StoreOrderStatus status = StoreOrderStatus.PENDING;

    /** When the buyer accepted immediate access / loss of the right of withdrawal in Checkout. */
    private LocalDateTime consentAcceptedAt;

    private LocalDateTime paidAt;

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
