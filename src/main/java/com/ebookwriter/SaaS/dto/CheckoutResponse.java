package com.ebookwriter.SaaS.dto;

import java.util.UUID;

public record CheckoutResponse(UUID orderId, String url) {
}
