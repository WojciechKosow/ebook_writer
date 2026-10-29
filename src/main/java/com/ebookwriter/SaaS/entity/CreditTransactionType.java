package com.ebookwriter.SaaS.entity;

/** Why a credit balance changed. */
public enum CreditTransactionType {
    /** Credits granted by a (renewing) subscription payment — the period's allowance. */
    SUBSCRIPTION_GRANT,
    /** The unused rest of a billing period's allowance, expired at renewal or when PRO ends. */
    SUBSCRIPTION_EXPIRY,
    /** Credits bought via a one-time pack. */
    CREDIT_PURCHASE,
    /** Credits held for (and finally spent on) an ebook generation. */
    GENERATION,
    /** Credits returned after a failed or cancelled generation, or a failed paid operation. */
    GENERATION_REFUND,
    /**
     * Credits returned after a successful generation once the real rendered
     * page count and generated images are known and came in under the reserved
     * hold — i.e. the unused part of the up-front hold.
     */
    GENERATION_ADJUSTMENT,
    /** Credits spent regenerating a book's AI cover. */
    COVER_GENERATION,
    /**
     * Credits reclaimed after a Stripe refund or chargeback — only from credits
     * that payment granted and that are still unspent; never below zero.
     */
    REFUND_CLAWBACK,
    /** Optional one-time grant when the wallet is created. */
    SIGNUP_BONUS,
    /** One-time conversion of a legacy (1 credit = 1 page) balance into current credits. */
    ECONOMY_MIGRATION
}
