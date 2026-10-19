package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Put a product on sale: one of the author's finished ebooks (title defaults to
 * the ebook's title), or — with no {@code ebookId} — a standalone product whose
 * buyers get the files uploaded to it (title required).
 */
public record StoreProductCreateRequest(
        UUID ebookId,
        @NotNull Long priceCents,
        @Size(max = 200) String title,
        @Size(max = 2000) String description,
        /** Put it first in the store (the featured product). */
        Boolean featured
) {
}
