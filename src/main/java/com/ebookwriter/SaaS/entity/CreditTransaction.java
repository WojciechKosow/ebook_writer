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
        @Index(columnList = "userId, createdAt"),
        @Index(columnList = "stripeReference")
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

    /**
     * Signed part of {@link #amount} that moved the monthly subscription credits
     * (the rest moved the purchased, non-expiring credits). E.g. a 500-credit
     * hold taken as 300 monthly + 200 purchased has amount -500 and
     * subscriptionAmount -300. 0 on rows written before the split existed.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private int subscriptionAmount = 0;

    /**
     * On REFUND_CLAWBACK rows: credits the refunded payment granted that could
     * not be reclaimed because they were already spent (the balance is never
     * pushed below zero). Recorded for audit and so repeated refund/dispute
     * events for the same payment stay idempotent.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private int shortfall = 0;

    /** The ebook involved, for GENERATION / GENERATION_REFUND. */
    private UUID ebookId;

    /** The payment order involved, for CREDIT_PURCHASE. */
    private UUID paymentOrderId;

    /**
     * The Stripe object this row relates to: the invoice id on SUBSCRIPTION_GRANT
     * rows (one allowance per paid invoice, however often the event is
     * delivered) and the payment_intent on REFUND_CLAWBACK rows (repeated
     * refund/chargeback events for the same charge stay idempotent).
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
