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

    /**
     * The minimum credit balance required to <b>start</b> a standard ebook
     * generation. Credits are the generation <em>budget</em>, not a promise of an
     * exact page count: this is the smallest budget Scrivetta needs to produce a
     * complete, worthwhile standard ebook. A user below this cannot begin (the UI
     * shows "you need at least N credits"); a user at or above it can, and only
     * ever pays for the pages actually rendered (unused credits stay on the
     * account). It is deliberately <em>not</em> "N credits = N pages".
     */
    private int minGenerationBudget = 30;

    /**
     * The lower end of the orientational page range for a standard ebook — used
     * only to describe the expected result to the user ("estimated usage ~20–30
     * credits"). It is guidance, never a floor the model must reach: a simpler
     * topic may naturally finish shorter.
     */
    private int standardTargetMinPages = 20;

    /**
     * The upper end of the orientational page range for a standard ebook. Used
     * only to describe the expected result ("usually 20–30 pages") — it is NOT a
     * cap on length. The real length follows the topic: a richer brief (more
     * chapters, a broader scope) produces a longer book, and the only hard limit
     * is the user's own credit budget (see {@link #maxGenerationBudget}). A simpler
     * topic ends sooner.
     */
    private int standardTargetPages = 30;

    /**
     * The absolute ceiling, in pages, for a single generation — a safety bound so
     * one book can never reserve or render an unbounded amount, however large the
     * user's balance. The real per-book ceiling is
     * {@code min(balance, maxGenerationBudget) + maxOverdraft}: the book may run as
     * long as the topic needs, limited only by what the user can afford (plus the
     * small overdraft), and is trued up to the real page count afterwards so unused
     * credits are refunded. This is deliberately generous — it exists to stop a
     * runaway, not to cut a legitimately long book short. A user with enough
     * credits gets the whole book; the book only winds down to a natural ending
     * when it approaches the user's actual budget.
     */
    private int maxGenerationBudget = 250;
}
