package com.ebookwriter.SaaS.dto.store;

import java.util.UUID;

public record StoreProductDTO(
        UUID id,
        UUID ebookId,
        String title,
        String description,
        String highlights,
        long priceCents,
        String currency,
        boolean active,
        int sortOrder,
        /** Pages of the finished PDF (0 if unknown). */
        int pageCount,
        /** Relative API path of the cover image (first page of the PDF). */
        String coverUrl
) {
}
