package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The ONLY thing a buyer sends: which product. Price, seller and status come from our database. */
public record StoreCheckoutRequest(@NotNull UUID productId) {
}
