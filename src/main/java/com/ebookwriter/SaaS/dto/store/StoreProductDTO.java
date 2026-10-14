package com.ebookwriter.SaaS.dto.store;

import com.ebookwriter.SaaS.entity.StoreProduct;

import java.util.UUID;

public record StoreProductDTO(
        UUID id,
        UUID ebookId,
        String title,
        String description,
        long priceCents,
        String currency,
        boolean active
) {
    public static StoreProductDTO from(StoreProduct p) {
        return new StoreProductDTO(p.getId(), p.getEbookId(), p.getTitle(), p.getDescription(),
                p.getPriceCents(), p.getCurrency(), p.isActive());
    }
}
