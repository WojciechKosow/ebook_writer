package com.ebookwriter.SaaS.service.store;

import com.stripe.exception.StripeException;
import com.stripe.param.checkout.SessionCreateParams;

import java.util.List;

/**
 * The only place creator-store code talks to Stripe. Kept behind an interface
 * so tests can replace it without any network call.
 */
public interface StripeConnectGateway {

    /** Create a connected account (controller properties, full Stripe Dashboard). Returns acct_... */
    String createAccount(String email, String country, String storeId) throws StripeException;

    /** A fresh, single-use Stripe-hosted onboarding link for the account. */
    String createAccountLink(String accountId, String refreshUrl, String returnUrl) throws StripeException;

    /** Fetch the account's current state from Stripe. */
    AccountState retrieveAccount(String accountId) throws StripeException;

    /** Create a Checkout Session ON the connected account (direct charge, Stripe-Account header). */
    CreatedSession createCheckoutSession(String accountId, SessionCreateParams params) throws StripeException;

    record AccountState(String id, boolean chargesEnabled, boolean detailsSubmitted, List<String> currentlyDue) {
    }

    record CreatedSession(String id, String url) {
    }
}
