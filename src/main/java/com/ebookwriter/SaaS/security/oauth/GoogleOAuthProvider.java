package com.ebookwriter.SaaS.security.oauth;

import com.ebookwriter.SaaS.config.properties.GoogleOAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Google sign-in via OpenID Connect (authorization code flow + PKCE, run entirely
 * server-side). Requests only {@code openid email profile} — authentication, no
 * Google API access.
 *
 * <p>The ID token is validated with Google's published JWKS (RS256 signature),
 * plus issuer, audience (our client id), {@code azp}, expiry and the per-login
 * nonce. The Google access token is never used or stored.
 */
@Slf4j
@Component
public class GoogleOAuthProvider implements OAuthProvider {

    public static final String ID = "google";

    static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    static final String SCOPES = "openid email profile";

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;
    private final JwtDecoder idTokenDecoder;

    @Autowired
    public GoogleOAuthProvider(GoogleOAuthProperties properties) {
        this(properties, defaultRestClient(), NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build());
    }

    /** Test seam: inject the HTTP client and a decoder (e.g. one backed by a local key). */
    GoogleOAuthProvider(GoogleOAuthProperties properties, RestClient restClient, JwtDecoder decoder) {
        this.properties = properties;
        this.restClient = restClient;
        this.idTokenDecoder = decoder;
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            nimbus.setJwtValidator(idTokenValidator(properties.getClientId()));
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public URI authorizationUri(String state, String nonce, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                // Let people with several Google accounts pick the right one.
                .queryParam("prompt", "select_account")
                .encode()
                .build()
                .toUri();
    }

    @Override
    public ExternalIdentity exchangeCode(String code, String codeVerifier, String expectedNonce) {
        String idToken = redeemCode(code, codeVerifier);
        Jwt jwt = decode(idToken);

        String nonce = jwt.getClaimAsString("nonce");
        if (nonce == null || !constantTimeEquals(nonce, expectedNonce)) {
            throw new OAuthException(OAuthErrorCode.INVALID_STATE, "Google ID token nonce mismatch");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new OAuthException(OAuthErrorCode.PROVIDER_ERROR, "Google ID token has no subject");
        }

        // email_verified is a JSON boolean in Google ID tokens; anything other
        // than an explicit true counts as unverified.
        boolean emailVerified = Boolean.TRUE.equals(jwt.getClaims().get("email_verified"));

        return new ExternalIdentity(
                ID,
                subject,
                jwt.getClaimAsString("email"),
                emailVerified,
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("picture"));
    }

    // ------------------------------------------------------------------------

    private String redeemCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("redirect_uri", properties.getRedirectUri());

        Map<?, ?> body;
        try {
            body = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            // Google's error body is {"error": "...", "error_description": "..."} —
            // no secrets. invalid_grant = code expired / already redeemed.
            String error = extractError(e.getResponseBodyAsString());
            if ("invalid_grant".equals(error)) {
                throw new OAuthException(OAuthErrorCode.CODE_EXPIRED,
                        "Google rejected the authorization code (invalid_grant)");
            }
            throw new OAuthException(OAuthErrorCode.PROVIDER_ERROR,
                    "Google token endpoint returned HTTP " + e.getStatusCode().value() + " (" + error + ")");
        } catch (RestClientException e) {
            throw new OAuthException(OAuthErrorCode.PROVIDER_ERROR,
                    "Google token endpoint unreachable: " + e.getClass().getSimpleName());
        }

        Object idToken = body == null ? null : body.get("id_token");
        if (!(idToken instanceof String s) || s.isBlank()) {
            throw new OAuthException(OAuthErrorCode.PROVIDER_ERROR, "Google token response has no id_token");
        }
        return s;
    }

    private Jwt decode(String idToken) {
        try {
            return idTokenDecoder.decode(idToken);
        } catch (JwtException e) {
            // The message names the failing check (issuer, audience, expiry...), not the token.
            throw new OAuthException(OAuthErrorCode.PROVIDER_ERROR,
                    "Google ID token failed validation: " + e.getMessage());
        }
    }

    static OAuth2TokenValidator<Jwt> idTokenValidator(String clientId) {
        OAuth2TokenValidator<Jwt> issuer = jwt -> {
            String iss = jwt.getClaimAsString("iss");
            return iss != null && ISSUERS.contains(iss)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("invalid issuer");
        };
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            if (jwt.getAudience() == null || !jwt.getAudience().contains(clientId)) {
                return failure("invalid audience");
            }
            String azp = jwt.getClaimAsString("azp");
            if (azp != null && !azp.equals(clientId)) {
                return failure("invalid authorized party");
            }
            return OAuth2TokenValidatorResult.success();
        };
        OAuth2TokenValidator<Jwt> expiryPresent = jwt -> jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : failure("missing exp");
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), expiryPresent, issuer, audience);
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private static String extractError(String responseBody) {
        if (responseBody == null) return "unknown";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"error\"\\s*:\\s*\"([a-z_]{1,64})\"")
                .matcher(responseBody);
        return m.find() ? m.group(1) : "unknown";
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static RestClient defaultRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
}
