package com.ebookwriter.SaaS.entity;

public enum PaymentOrderStatus {
    /** Order row created, Checkout session not yet created. */
    CREATED,
    /** Checkout session created, awaiting payment. */
    PENDING,
    /** Payment succeeded and the order was fulfilled (credits granted). */
    PAID,
    /** Payment failed. */
    FAILED,
    /** Checkout session expired before payment. */
    EXPIRED
}
