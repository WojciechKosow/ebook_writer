package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Size;

/** Partial update: null fields are left unchanged. */
public record StoreProductUpdateRequest(
        @Size(max = 200) String title,
        @Size(max = 2000) String description,
        Long priceCents,
        Boolean active
) {
}
