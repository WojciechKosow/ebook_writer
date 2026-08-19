package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.CreditPack;

public record CreditPackDTO(
        String id,
        int credits,
        int priceCents,
        String displayName
) {
    public static CreditPackDTO from(CreditPack pack) {
        return new CreditPackDTO(pack.name(), pack.getCredits(), pack.getPriceCents(), pack.getDisplayName());
    }
}
