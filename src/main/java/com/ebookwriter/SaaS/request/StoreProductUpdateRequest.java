package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Size;

/** Partial update: null fields are left unchanged. */
public record StoreProductUpdateRequest(
        @Size(max = 200) String title,
        @Size(max = 2000) String description,
        /** "What you'll be able to do", one point per line. */
        @Size(max = 1500) String highlights,
        Long priceCents,
        Boolean active,
        /** The page design (JSON). Null = unchanged, blank = back to defaults. */
        @Size(max = 30_000, message = "This page design is too large") String pageSettings
) {
}
