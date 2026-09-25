package com.ebookwriter.SaaS.security.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuthStateServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32bytes";
    private static final String CLIENT_STATE = "c".repeat(43);

    private final OAuthStateService service = new OAuthStateService(SECRET);

    @Test
    void roundTripRecoversNonceVerifierAndClientBinding() {
        OAuthStateService.Started started = service.start("google", CLIENT_STATE);

        OAuthStateService.Verified verified = service.verify(started.cookieValue(), "google", started.state());

        assertEquals(started.nonce(), verified.nonce());
        // PKCE S256: challenge = b64url(sha256(verifier))
        assertEquals(started.codeChallenge(), OAuthStateService.sha256(verified.codeVerifier()));
        assertEquals(OAuthStateService.sha256(CLIENT_STATE), verified.clientStateHash());
    }

    @Test
    void everyLoginGetsFreshRandomValues() {
        OAuthStateService.Started a = service.start("google", CLIENT_STATE);
        OAuthStateService.Started b = service.start("google", CLIENT_STATE);
        assertNotEquals(a.state(), b.state());
        assertNotEquals(a.nonce(), b.nonce());
        assertNotEquals(a.codeChallenge(), b.codeChallenge());
    }

    @Test
    void rejectsStateMismatch() {
        OAuthStateService.Started started = service.start("google", CLIENT_STATE);
        assertInvalidState(() -> service.verify(started.cookieValue(), "google", "attacker-state"));
    }

    @Test
    void rejectsMissingCookieOrState() {
        OAuthStateService.Started started = service.start("google", CLIENT_STATE);
        assertInvalidState(() -> service.verify(null, "google", started.state()));
        assertInvalidState(() -> service.verify(started.cookieValue(), "google", null));
    }

    @Test
    void rejectsTamperedCookie() {
        OAuthStateService.Started started = service.start("google", CLIENT_STATE);
        String cookie = started.cookieValue();
        String tampered = cookie.substring(0, cookie.length() - 3) + (cookie.endsWith("AAA") ? "BBB" : "AAA");
        assertInvalidState(() -> service.verify(tampered, "google", started.state()));
    }

    @Test
    void rejectsCookieSignedWithAnotherKey() {
        OAuthStateService other = new OAuthStateService("another-secret-another-secret-another-32b");
        OAuthStateService.Started started = other.start("google", CLIENT_STATE);
        assertInvalidState(() -> service.verify(started.cookieValue(), "google", started.state()));
    }

    @Test
    void rejectsCookieFromAnotherProvider() {
        OAuthStateService.Started started = service.start("google", CLIENT_STATE);
        assertInvalidState(() -> service.verify(started.cookieValue(), "microsoft", started.state()));
    }

    @Test
    void rejectsMalformedClientState() {
        OAuthException e = assertThrows(OAuthException.class, () -> service.start("google", "short"));
        assertEquals(OAuthErrorCode.INVALID_REQUEST, e.getErrorCode());
        assertThrows(OAuthException.class, () -> service.start("google", null));
        assertThrows(OAuthException.class, () -> service.start("google", "x".repeat(40) + "<script>"));
    }

    private static void assertInvalidState(org.junit.jupiter.api.function.Executable call) {
        OAuthException e = assertThrows(OAuthException.class, call);
        assertEquals(OAuthErrorCode.INVALID_STATE, e.getErrorCode());
    }
}
