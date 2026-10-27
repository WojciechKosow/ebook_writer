package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A user's current credit balance, keyed by user id. The balance is a
 * materialised running total; every change is also recorded as a
 * {@link CreditTransaction}. Changes take the row under a pessimistic lock
 * ({@code SELECT ... FOR UPDATE}) so concurrent generations can never overspend.
 *
 * <p>The balance has two parts with different expiry rules:
 * <ul>
 *   <li>{@link #subscriptionCredits} — what is left of the current PRO billing
 *       period's allowance; replaced (not added to) at each renewal and expired
 *       when the subscription ends;</li>
 *   <li>purchased credits ({@code balance - subscriptionCredits}) — credit packs
 *       (and converted legacy credits), which never expire. Negative only while a
 *       bounded generation overdraft is outstanding.</li>
 * </ul>
 * Spending takes subscription credits first. Invariant:
 * {@code 0 <= subscriptionCredits <= max(0, balance)}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "credit_balances")
public class CreditBalance {

    /** Wallets in the old economy, where one credit bought one finished page. */
    public static final int ECONOMY_LEGACY = 1;
    /** Wallets in the current economy (credits = AI usage budget). */
    public static final int ECONOMY_CURRENT = 2;

    @Id
    private UUID userId;

    /** Total spendable credits (subscription + purchased). */
    @Column(nullable = false)
    private int balance;

    /** The part of {@link #balance} that is this billing period's PRO allowance. */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private int subscriptionCredits = 0;

    /**
     * Which economy the balance is denominated in. Rows that existed before the
     * credit redesign get the DB default ({@link #ECONOMY_LEGACY}) and are
     * converted once by {@code LegacyCreditMigration}; new wallets start current.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "integer not null default 1")
    private int economyVersion = ECONOMY_CURRENT;

    /** Credits from packs (and converted legacy credits) — they never expire. */
    public int purchasedCredits() {
        return balance - subscriptionCredits;
    }
}
