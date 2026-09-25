package com.ebookwriter.SaaS.security.oauth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * Per-login OAuth transaction state, kept in a short-lived, signed, HttpOnly
 * cookie on the backend origin (the API is stateless — no server session).
 *
 * <p>The cookie binds the Google callback to the browser that started the
 * login: it carries the {@code state} (CSRF protection for the callback), the
 * OIDC {@code nonce} (ID-token replay protection), the PKCE {@code code_verifier},
 * and a hash of the frontend's {@code clientState} (binds the final one-time
 * login code to the tab that asked for it). It is HMAC-signed with a key derived
 * from {@code jwt.secret} — distinct from the access-token key — and expires
 * after {@link #TTL}.
 */
@Component
public class OAuthStateService {

    public static final Duration TTL = Duration.ofMinutes(10);
    private static final String TYPE = "oauth_state";
    private static final Pattern CLIENT_STATE = Pattern.compile("^[A-Za-z0-9_-]{32,128}$");

    private final SecureRandom random = new SecureRandom();
    private final Key signingKey;

    public OAuthStateService(@Value("${jwt.secret}") String jwtSecret) {
        this.signingKey = Keys.hmacShaKeyFor(deriveKey(jwtSecret));
    }

    /** A freshly started login: values for the provider redirect plus the cookie to set. */
    public record Started(String state, String nonce, String codeChallenge, String cookieValue) {
    }

    /** What the callback needs, recovered from a valid cookie. */
    public record Verified(String nonce, String codeVerifier, String clientStateHash) {
    }

    public Started start(String provider, String clientState) {
        if (clientState == null || !CLIENT_STATE.matcher(clientState).matches()) {
            throw new OAuthException(OAuthErrorCode.INVALID_REQUEST, "Missing or malformed client_state");
        }

        String state = randomToken();
        String nonce = randomToken();
        String codeVerifier = randomToken();

        Date now = new Date();
        String cookie = Jwts.builder()
                .claim("typ", TYPE)
                .claim("prv", provider)
                .claim("st", state)
                .claim("nc", nonce)
                .claim("cv", codeVerifier)
                .claim("cs", sha256(clientState))
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + TTL.toMillis()))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        return new Started(state, nonce, sha256(codeVerifier), cookie);
    }

    /**
     * Verify the cookie from the callback request against the {@code state}
     * query parameter Google echoed back.
     *
     * @throws OAuthException INVALID_STATE on any mismatch, tampering or expiry
     */
    public Verified verify(String cookieValue, String provider, String stateParam) {
        if (cookieValue == null || cookieValue.isBlank() || stateParam == null || stateParam.isBlank()) {
            throw new OAuthException(OAuthErrorCode.INVALID_STATE, "Missing OAuth state cookie or parameter");
        }

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(cookieValue)
                    .getBody();
        } catch (JwtException | IllegalArgumentException e) {
            throw new OAuthException(OAuthErrorCode.INVALID_STATE,
                    "OAuth state cookie invalid or expired (" + e.getClass().getSimpleName() + ")");
        }

        if (!TYPE.equals(claims.get("typ", String.class))
                || !provider.equals(claims.get("prv", String.class))) {
            throw new OAuthException(OAuthErrorCode.INVALID_STATE, "OAuth state cookie type/provider mismatch");
        }

        String expectedState = claims.get("st", String.class);
        if (expectedState == null || !MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                stateParam.getBytes(StandardCharsets.UTF_8))) {
            throw new OAuthException(OAuthErrorCode.INVALID_STATE, "OAuth state parameter mismatch");
        }

        return new Verified(
                claims.get("nc", String.class),
                claims.get("cv", String.class),
                claims.get("cs", String.class));
    }

    /** Base64url(SHA-256(value)) — used for the PKCE S256 challenge and the clientState binding. */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] deriveKey(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("scrivetta/oauth-state/v1".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive OAuth state key", e);
        }
    }
}
