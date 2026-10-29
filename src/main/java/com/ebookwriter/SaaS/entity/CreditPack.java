package com.ebookwriter.SaaS.entity;

/**
 * The one-time credit packs available for purchase. Prices are in USD cents.
 * Kept as an enum so the server is the source of truth for what a pack costs —
 * the client can never dictate price. Checkout sends the price inline, so no
 * Stripe Price object is needed per pack.
 *
 * <p>Pack credits never expire and are spent after the monthly PRO credits.
 *
 * <p>The retired packs stay in the enum so historical {@link PaymentOrder}
 * rows (stored by name) still load; they are not offered or purchasable.
 */
public enum CreditPack {
    STARTER(500, 799, "Starter", true),
    CREATOR(1200, 1999, "Creator", true),
    STUDIO(2500, 3499, "Studio", true),

    /** Retired: legacy page credits (1 credit = 1 page). */
    PACK_25(25, 500, "25 credits (legacy)", false),
    /** Retired: legacy page credits (1 credit = 1 page). */
    PACK_60(60, 900, "60 credits (legacy)", false),
    /** Retired: legacy page credits (1 credit = 1 page). */
    PACK_150(150, 1900, "150 credits (legacy)", false);

    private final int credits;
    private final int priceCents;
    private final String displayName;
    private final boolean available;

    CreditPack(int credits, int priceCents, String displayName, boolean available) {
        this.credits = credits;
        this.priceCents = priceCents;
        this.displayName = displayName;
        this.available = available;
    }

    public int getCredits() {
        return credits;
    }

    public int getPriceCents() {
        return priceCents;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Whether the pack can be bought now (retired packs only exist for history). */
    public boolean isAvailable() {
        return available;
    }
}
