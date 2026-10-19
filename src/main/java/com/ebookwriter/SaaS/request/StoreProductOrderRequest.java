package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Every product of the store, in the order buyers should see them (first = featured). */
public record StoreProductOrderRequest(
        @NotNull @Size(max = 500) List<UUID> productIds
) {
}
