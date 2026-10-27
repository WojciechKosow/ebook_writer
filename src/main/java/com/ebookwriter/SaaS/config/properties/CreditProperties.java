package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credit economy configuration. Prefix: credits.
 *
 * <p>Credits are an internal <b>AI usage budget</b>, not pages. Every paid AI
 * operation has its own deterministic credit cost ({@link Pricing}), set from the
 * models and workflows it actually runs (see docs/PRICING.md for the derivation).
 * PRO ($29.99/month) grants {@code subscriptionMonthlyGrant} credits per billing
 * period; one-time credit packs ({@code CreditPack}) top up a separate,
 * non-expiring balance.
 *
 * <p>Inside the generation engine a book is still measured and capped in
 * <em>pages</em> (that is what the PDF renderer produces); credits are only
 * derived from pages at the billing boundary, via {@link Pricing#getCreditsPerPage()}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "credits")
public class CreditProperties {

    /**
     * Credits granted for each paid PRO billing period. Monthly credits do not
     * roll over: the unused rest of the previous period expires when the next
     * period's allowance is granted.
     */
    private int subscriptionMonthlyGrant = 3000;

    /**
     * Credits granted per billing period to subscribers still on a legacy
     * (pre-3,000-credit) Stripe price, listed in {@code stripe.legacy-subscription-price-ids}.
     * The default keeps what the old $19.99 plan paid for — 150 finished pages a
     * month — in the new unit: 150 × {@link Pricing#getCreditsPerPage()} (11) = 1,650.
     */
    private int legacySubscriptionMonthlyGrant = 1650;

    /** Credits granted once when a user's wallet is first created (0 = none). */
    private int signupBonus = 0;

    /**
     * The single, central overdraft tolerance for ebook generation, in
     * <b>pages</b>. Because the model cannot guarantee an exact page count, a book
     * may finish a little longer than the user can strictly afford: the up-front
     * hold covers up to this many extra pages, so the balance can dip at most
     * {@code maxOverdraft × creditsPerPage} credits below zero, and no further —
     * the render is capped at the hold. The deficit is settled by the next
     * credits the user receives.
     */
    private int maxOverdraft = 10;

    /**
     * The per-book safety maximum, in pages: the most any single generation may
     * plan, reserve or render, however large the user's balance or materials. It
     * is not a length the user picks and not a target — books are as long as
     * their content at the selected depth; this only stops a runaway (and so an
     * unexpected cost spike).
     */
    private int maxGenerationBudget = 400;

    private Pricing pricing = new Pricing();

    private Migration migration = new Migration();

    /**
     * Operation-specific credit costs. Calibrated so that our reference workload
     * — a ~265-page text-led ebook with up to 6 illustrations and a cover,
     * ≈ $13–14 of combined Claude/OpenAI cost — uses about 3,000 credits
     * (265 × 11 + 6 × 15 + 30 ≈ 3,035). Knowledge analysis, the blueprint and the
     * scope assessment run on a small OpenAI model and cost no credits.
     */
    @Getter
    @Setter
    public static class Pricing {

        /**
         * Credits per final rendered page of a generated book. Covers planning,
         * chapter writing and the editorial pass on Claude — the bulk of the AI
         * cost, which scales with the length of the book.
         */
        private int creditsPerPage = 11;

        /** Credits per AI-generated illustration placed in a book. */
        private int creditsPerImage = 15;

        /**
         * Credits per AI cover visual (generated with the book, or regenerated on
         * request) — a planning call plus a high-quality image.
         */
        private int creditsPerCover = 30;
    }

    /**
     * The one-time conversion of wallets created under the old economy, where one
     * credit bought one finished page. See {@code LegacyCreditMigration}.
     */
    @Getter
    @Setter
    public static class Migration {

        /** Run the conversion at startup (idempotent: converted wallets are skipped). */
        private boolean enabled = true;

        /**
         * New credits per legacy credit. 0 = {@link Pricing#getCreditsPerPage()}: a
         * legacy credit bought one finished page, and a finished page costs
         * {@code creditsPerPage} credits now, so the balance keeps what it buys.
         */
        private int legacyCreditValue = 0;
    }

    /** New credits per legacy (1 credit = 1 page) credit. */
    public int resolveLegacyCreditValue() {
        int value = migration.getLegacyCreditValue();
        return value > 0 ? value : Math.max(1, pricing.getCreditsPerPage());
    }
}
