package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe configuration. Prefix: stripe.
 *
 * All secrets come from the environment — never commit real keys.
 *   stripe.secret-key            = sk_test_... / sk_live_...
 *   stripe.webhook-secret        = whsec_...   (from `stripe listen` or the dashboard)
 *   stripe.subscription-price-id = price_...    (the recurring PRO price, $29.99/mo)
 *   stripe.legacy-subscription-price-ids = price_...,price_...  (older PRO prices
 *                                  existing subscribers may still be on; their
 *                                  renewals grant credits.legacy-subscription-monthly-grant)
 *   stripe.success-url           = frontend URL Checkout returns to on success
 *   stripe.cancel-url            = frontend URL Checkout returns to on cancel
 *
 * Fulfilment happens in the webhook (never the browser redirect), so a user who
 * closes the tab after paying still gets their credits. {ORDER_ID} in the
 * success URL is substituted with our PaymentOrder id so the page can poll for
 * the real status.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String secretKey = "";
    private String webhookSecret = "";

    /** Stripe Price id of the PRO plan new subscriptions are sold at ($29.99/month). */
    private String subscriptionPriceId = "";

    /**
     * Older PRO Price ids (e.g. the retired $19.99/month price) that existing
     * subscribers may still renew on. Renewals on these grant
     * {@code credits.legacy-subscription-monthly-grant} instead of the full PRO
     * allowance, so a subscriber keeps exactly what they pay for. Empty = every
     * subscription invoice grants the current allowance.
     */
    private java.util.List<String> legacySubscriptionPriceIds = new java.util.ArrayList<>();

    private String successUrl = "http://localhost:3000/billing/success?order={ORDER_ID}";
    private String cancelUrl = "http://localhost:3000/billing";

    /** Where the Stripe billing portal returns the user after they're done. */
    private String portalReturnUrl = "http://localhost:3000/billing";

    private String currency = "usd";
}
