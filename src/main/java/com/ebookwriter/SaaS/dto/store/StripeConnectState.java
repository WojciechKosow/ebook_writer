package com.ebookwriter.SaaS.dto.store;

/** The three states the store dashboard shows for the author's Stripe account. */
public enum StripeConnectState {
    NOT_CONNECTED,
    /** An account exists but Stripe has not enabled charges yet. */
    ACTION_REQUIRED,
    /** {@code charges_enabled = true}, as last fetched from Stripe. */
    READY
}
