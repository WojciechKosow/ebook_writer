package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Move a product one place up or down in the store. */
public record StoreProductMoveRequest(@NotNull @Pattern(regexp = "^(up|down)$") String direction) {
}
