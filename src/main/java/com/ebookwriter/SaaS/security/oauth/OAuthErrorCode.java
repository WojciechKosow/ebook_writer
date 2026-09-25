package com.ebookwriter.SaaS.security.oauth;

/**
 * Stable, non-sensitive error codes for a failed OAuth login. The value is what
 * the frontend receives ({@code /auth/callback?error=<code>}) and maps to a
 * user-facing message — never a stack trace or provider detail.
 */
public enum OAuthErrorCode {
    /** The user cancelled or denied consent on the provider's screen. */
    ACCESS_DENIED("access_denied"),
    /** Missing/tampered/expired state cookie, state mismatch or nonce mismatch. */
    INVALID_STATE("invalid_state"),
    /** Callback without a code, or with parameters we don't understand. */
    INVALID_REQUEST("invalid_request"),
    /** The authorization code was rejected (expired / already used). */
    CODE_EXPIRED("code_expired"),
    /** Provider unreachable, token endpoint error, or an ID token that failed validation. */
    PROVIDER_ERROR("provider_error"),
    /** The provider did not confirm the email address. */
    EMAIL_NOT_VERIFIED("email_not_verified"),
    /** The Scrivetta account with this email is already linked to a different account at the provider. */
    ACCOUNT_CONFLICT("account_conflict"),
    /** The one-time login code is invalid, used or expired. */
    INVALID_LOGIN_CODE("invalid_login_code"),
    /** The provider is not configured on this server. */
    NOT_CONFIGURED("not_configured"),
    /** Anything else (database, session creation). */
    SERVER_ERROR("server_error");

    private final String code;

    OAuthErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
