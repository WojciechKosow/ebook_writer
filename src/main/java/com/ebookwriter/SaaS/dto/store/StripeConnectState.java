package com.ebookwriter.SaaS.dto.store;

/** The three states the store dashboard shows for the author's Stripe account. */
public enum StripeConnectState {
    NOT_CONNECTED,
    /** An account exists but Stripe has not enabled charges yet. */
    ACTION_REQUIRED,
    /**
     * The author submitted everything Stripe asked for and nothing is due, but
     * Stripe hasn't activated card payments yet (it is still verifying). Nothing
     * for the author to do — the status flips when Stripe finishes.
     */
    PENDING_VERIFICATION,
    /** {@code charges_enabled = true}, as last fetched from Stripe. */
    READY
}
