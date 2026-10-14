package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Pattern;

/**
 * Optional ISO 3166-1 alpha-2 country of the author's business. Only used when
 * the connected account is first created (Stripe can't change it afterwards).
 */
public record StripeConnectRequest(
        @Pattern(regexp = "^[A-Z]{2}$", message = "Use a two-letter country code") String country
) {
}
