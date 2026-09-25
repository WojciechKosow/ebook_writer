package com.ebookwriter.SaaS.entity;

public enum TokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /** One-time code handing a completed OAuth login from the backend redirect to the frontend. */
    OAUTH_LOGIN
}
