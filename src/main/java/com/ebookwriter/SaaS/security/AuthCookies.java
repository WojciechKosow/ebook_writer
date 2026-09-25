package com.ebookwriter.SaaS.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds the auth cookies so every endpoint that sets them uses identical attributes. */
@Component
public class AuthCookies {

    public static final String REFRESH_COOKIE = "refreshToken";
    public static final String OAUTH_STATE_COOKIE = "oauth2_state";
    /** The OAuth state cookie is only ever sent back to the OAuth endpoints. */
    public static final String OAUTH_STATE_PATH = "/api/auth/oauth2";

    // Cross-site by default: the frontend (e.g. Vercel) and backend (e.g.
    // Railway) are different sites, so the refresh cookie must be SameSite=None
    // + Secure to be sent on /refresh and /logout. Override for same-site setups.
    @Value("${app.auth.cookie-same-site:None}")
    private String cookieSameSite;

    @Value("${app.auth.cookie-secure:true}")
    private boolean cookieSecure;

    public ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    /**
     * OAuth transaction cookie. SameSite=Lax: it is set and read only during
     * top-level navigations on the backend origin (start → Google → callback),
     * and Lax cookies are sent on the top-level GET redirect back from Google
     * while still being withheld from cross-site subrequests.
     */
    public ResponseCookie oauthStateCookie(String value, Duration maxAge) {
        return ResponseCookie.from(OAUTH_STATE_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(OAUTH_STATE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
