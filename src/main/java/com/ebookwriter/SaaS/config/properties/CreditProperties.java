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

    /**
     * Head-room over the requested page count when reserving credits for a
     * generation, as a fraction (e.g. {@code 0.20} = +20%). We reserve
     * {@code min(requestedPages + requestedPages*tolerance, balance)} up front as
     * a ceiling: it lets a book run slightly past the requested length for a
     * clean ending, is always capped by what the user can actually pay, and the
     * unused part is refunded once the real page count is known. Set to
     * {@code 0} to hold exactly the requested page count.
     */
    private double pageBudgetTolerance = 0.20;
}
