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
 *   stripe.subscription-price-id = price_...    (the recurring $19.99/mo price)
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

    /** Stripe Price id for the single recurring subscription plan. */
    private String subscriptionPriceId = "";

    private String successUrl = "http://localhost:3000/billing/success?order={ORDER_ID}";
    private String cancelUrl = "http://localhost:3000/billing";

    private String currency = "usd";
}
