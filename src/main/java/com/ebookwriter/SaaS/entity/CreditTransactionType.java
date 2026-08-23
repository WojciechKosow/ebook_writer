package com.ebookwriter.SaaS.entity;

/** Why a credit balance changed. */
public enum CreditTransactionType {
    /** Credits granted by a (renewing) subscription payment. */
    SUBSCRIPTION_GRANT,
    /** Credits bought via a one-time pack. */
    CREDIT_PURCHASE,
    /** Credits spent starting an ebook generation. */
    GENERATION,
    /** Credits returned after a failed generation. */
    GENERATION_REFUND,
    /**
     * Credits returned after a successful generation once the real rendered
     * page count is known and it came in under the reserved budget — i.e. the
     * unused part of the up-front hold.
     */
    GENERATION_ADJUSTMENT,
    /** Credits reclaimed after a Stripe refund or chargeback (may go negative). */
    REFUND_CLAWBACK,
    /** Optional one-time grant when the wallet is created. */
    SIGNUP_BONUS
}
