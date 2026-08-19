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
    /** Optional one-time grant when the wallet is created. */
    SIGNUP_BONUS
}
