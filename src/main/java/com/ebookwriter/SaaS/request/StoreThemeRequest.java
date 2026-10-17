package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Size;

/**
 * The store page design as a JSON document (see {@code Store.themeSettings}).
 * Null or blank resets the store to the default (Scrivetta) style.
 */
public record StoreThemeRequest(
        @Size(max = StoreThemeRequest.MAX_LENGTH, message = "This design is too large") String settings
) {
    public static final int MAX_LENGTH = 200_000;
}
