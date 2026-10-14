package com.ebookwriter.SaaS.dto.store;

import java.util.UUID;

public record StoreCheckoutResponse(UUID orderId, String checkoutUrl) {
}
