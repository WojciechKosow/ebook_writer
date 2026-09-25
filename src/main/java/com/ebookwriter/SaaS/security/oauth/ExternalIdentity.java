package com.ebookwriter.SaaS.security.oauth;

/**
 * A verified identity asserted by an external provider — the provider-neutral
 * result of a completed OAuth/OIDC login. Only built from a validated ID token.
 *
 * @param provider      lower-case provider id, e.g. {@code google}
 * @param subject       the provider's stable user id (OIDC {@code sub})
 * @param email         email claim, may be null
 * @param emailVerified the provider's {@code email_verified} claim — true only
 *                      when the provider explicitly says so
 * @param name          display name, may be null
 * @param pictureUrl    avatar URL, may be null
 */
public record ExternalIdentity(
        String provider,
        String subject,
        String email,
        boolean emailVerified,
        String name,
        String pictureUrl
) {
}
