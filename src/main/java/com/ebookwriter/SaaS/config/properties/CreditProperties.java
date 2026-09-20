package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credit economy configuration. Prefix: credits.
 *
 * The whole V0.1 economy in one sentence: a $19.99/month subscription grants
 * {@code subscriptionMonthlyGrant} credits, and <b>1 credit = 1 final generated
 * page</b>. The requested page count is only a target length, never a guaranteed
 * final size, so the real cost is read back from the rendered PDF.
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
     * The single, central overdraft limit for ebook generation. Because the model
     * cannot guarantee an exact page count, the requested length is only a target:
     * a book may finish a little longer than the user can strictly afford. We allow
     * the balance to dip up to this many credits <b>below zero</b> (i.e. the lowest
     * possible balance from a generation is {@code -maxOverdraft}), and no further.
     *
     * <p>This is the one place the value lives — do not hardcode it elsewhere. It
     * both bounds the up-front hold and caps how many pages may be rendered, so a
     * generation can never drive the balance past {@code -maxOverdraft} however
     * long the model runs (a runaway 100–200 page book is trimmed to fit).
     */
    private int maxOverdraft = 10;
}
