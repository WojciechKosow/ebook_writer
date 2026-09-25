package com.ebookwriter.SaaS.security.oauth;

/**
 * A failed OAuth login step. The message is for server logs only (and must never
 * contain codes or tokens); clients only ever see {@link #getErrorCode()}.
 */
public class OAuthException extends RuntimeException {

    private final OAuthErrorCode errorCode;

    public OAuthException(OAuthErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OAuthException(OAuthErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public OAuthErrorCode getErrorCode() {
        return errorCode;
    }
}
