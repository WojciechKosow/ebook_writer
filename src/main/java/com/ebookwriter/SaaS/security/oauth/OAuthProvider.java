package com.ebookwriter.SaaS.security.oauth;

import java.net.URI;

/**
 * One external sign-in provider (Google today; Apple/Microsoft would be further
 * implementations). Provider-specific protocol details live behind this
 * interface; account resolution and session issuing are provider-neutral (see
 * {@code OAuthAccountService}).
 */
public interface OAuthProvider {

    /** Lower-case id used in URLs and stored on {@code UserIdentity}, e.g. {@code google}. */
    String id();

    /** False when credentials are missing — the flow then fails cleanly with NOT_CONFIGURED. */
    boolean isConfigured();

    /** The provider's authorization endpoint URL for a new login attempt. */
    URI authorizationUri(String state, String nonce, String codeChallenge);

    /**
     * Redeem the authorization code (server-to-server, with PKCE verifier and
     * client secret) and return the validated identity. Implementations MUST
     * verify the ID token (signature, issuer, audience, expiry) and that its
     * nonce equals {@code expectedNonce}.
     *
     * @throws OAuthException on any failure
     */
    ExternalIdentity exchangeCode(String code, String codeVerifier, String expectedNonce);
}
