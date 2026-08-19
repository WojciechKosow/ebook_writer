package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credit economy configuration. Prefix: credits.
 *
 * The whole V0.1 economy in one sentence: a $19.99/month subscription grants
 * {@code subscriptionMonthlyGrant} credits, and 1 credit ≈ 1 ebook page.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "credits")
public class CreditProperties {

    /** Credits granted on each successful monthly subscription payment. */
    private int subscriptionMonthlyGrant = 150;

    /** Credits granted once when a user's wallet is first created (0 = none). */
    private int signupBonus = 0;
}
