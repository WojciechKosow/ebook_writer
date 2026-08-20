package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An immutable ledger entry: one row per credit change, with enough context to
 * explain why the balance moved. {@code amount} is signed (+grant / -spend) and
 * {@code balanceAfter} snapshots the resulting balance.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "credit_transactions", indexes = {
        @Index(columnList = "userId, createdAt")
})
public class CreditTransaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditTransactionType type;

    /** Signed: positive for grants/refunds, negative for spends. */
    @Column(nullable = false)
    private int amount;

    /** Balance immediately after this transaction. */
    @Column(nullable = false)
    private int balanceAfter;

    /** The ebook involved, for GENERATION / GENERATION_REFUND. */
    private UUID ebookId;

    /** The payment order involved, for CREDIT_PURCHASE / SUBSCRIPTION_GRANT. */
    private UUID paymentOrderId;

    /**
     * The Stripe payment_intent this row relates to. Set on REFUND_CLAWBACK rows
     * so repeated refund/chargeback events for the same charge stay idempotent.
     */
    private String stripeReference;

    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
