package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "Continue with Google" (OAuth 2.0 authorization code + PKCE, OpenID Connect).
 * Prefix: google.oauth.
 *
 * All values come from the environment — never commit real credentials.
 *   google.oauth.client-id     = the OAuth client's "Client ID" (…apps.googleusercontent.com)
 *   google.oauth.client-secret = the OAuth client's "Client secret" (backend only)
 *   google.oauth.redirect-uri  = the BACKEND callback, registered verbatim in the
 *                                Google Cloud Console "Authorized redirect URIs":
 *                                {backend}/api/auth/oauth2/google/callback
 *
 * Left blank the app still boots; the Google button then fails with a clean
 * "not available" error instead of a broken redirect.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "google.oauth")
public class GoogleOAuthProperties {

    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "http://localhost:8080/api/auth/oauth2/google/callback";

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
