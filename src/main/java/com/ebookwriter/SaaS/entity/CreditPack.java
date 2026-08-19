package com.ebookwriter.SaaS.entity;

/**
 * The one-time credit packs available for purchase (V0.1). Prices are in USD
 * cents. Kept as an enum so the server is the source of truth for what a pack
 * costs — the client can never dictate price.
 */
public enum CreditPack {
    PACK_25(25, 500, "25 credits"),
    PACK_60(60, 900, "60 credits"),
    PACK_150(150, 1900, "150 credits");

    private final int credits;
    private final int priceCents;
    private final String displayName;

    CreditPack(int credits, int priceCents, String displayName) {
        this.credits = credits;
        this.priceCents = priceCents;
        this.displayName = displayName;
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
}
