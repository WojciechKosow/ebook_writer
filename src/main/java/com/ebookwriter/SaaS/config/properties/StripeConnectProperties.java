package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe Connect for creator stores. Prefix: stripe.connect.
 *
 * Deliberately separate from {@link StripeProperties}: the platform's own PRO
 * subscription may run on a live key while creator-store payments stay in test
 * mode. All values come from the environment — never commit real keys.
 *   stripe.connect.secret-key     = sk_test_... (the platform's key; used with the Stripe-Account header)
 *   stripe.connect.webhook-secret = whsec_...   (the Connect webhook endpoint's own signing secret)
 *   stripe.connect.live-enabled   = false       (a live key is refused unless this is true)
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "stripe.connect")
public class StripeConnectProperties {

    private String secretKey = "";
    private String webhookSecret = "";

    /**
     * Live mode switch. While false, a live key ({@code sk_live_} / {@code rk_live_})
     * is refused and live-mode webhook events are ignored, so live payments are
     * impossible without explicit production configuration.
     */
    private boolean liveEnabled = false;

    /** Minutes a Checkout Session stays open (Stripe allows 30 min – 24 h). */
    private int checkoutExpiryMinutes = 60;

    public boolean isLiveKey() {
        String key = secretKey == null ? "" : secretKey.trim();
        return key.startsWith("sk_live_") || key.startsWith("rk_live_");
    }
}
