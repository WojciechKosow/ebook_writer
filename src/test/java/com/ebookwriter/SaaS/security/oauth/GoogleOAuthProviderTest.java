package com.ebookwriter.SaaS.security.oauth;

import com.ebookwriter.SaaS.config.properties.GoogleOAuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Google OIDC: authorization URL shape, code exchange, and ID-token validation
 * (signature, iss, aud, azp, exp, nonce) against a locally generated RSA key.
 */
class GoogleOAuthProviderTest {

    private static final String CLIENT_ID = "client-123.apps.googleusercontent.com";
    private static final String NONCE = "nonce-abc";

    private KeyPair keys;
    private MockRestServiceServer server;
    private GoogleOAuthProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();

        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setClientId(CLIENT_ID);
        props.setClientSecret("secret");
        props.setRedirectUri("https://api.example.com/api/auth/oauth2/google/callback");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new GoogleOAuthProvider(props, builder.build(),
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build());
    }

    @Test
    void authorizationUriRequestsOnlyOidcScopesWithPkce() {
        URI uri = provider.authorizationUri("st", "nc", "challenge");
        Map<String, String> q = UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();

        assertTrue(uri.toString().startsWith(GoogleOAuthProvider.AUTHORIZATION_ENDPOINT));
        assertEquals(CLIENT_ID, q.get("client_id"));
        assertEquals("code", q.get("response_type"));
        assertEquals("openid%20email%20profile", q.get("scope"));
        assertEquals("st", q.get("state"));
        assertEquals("nc", q.get("nonce"));
        assertEquals("challenge", q.get("code_challenge"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertFalse(uri.toString().contains("secret"), "client secret must never reach the browser");
    }

    @Test
    void validIdTokenYieldsIdentity() throws Exception {
        expectTokenResponse(idToken(c -> { }));

        ExternalIdentity id = provider.exchangeCode("the-code", "verifier", NONCE);

        assertEquals("google", id.provider());
        assertEquals("sub-42", id.subject());
        assertEquals("jane@example.com", id.email());
        assertTrue(id.emailVerified());
        assertEquals("Jane Doe", id.name());
        server.verify();
    }

    @Test
    void sendsPkceVerifierAndSecretOnlyServerSide() throws Exception {
        server.expect(requestTo(GoogleOAuthProvider.TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "authorization_code",
                        "code", "the-code",
                        "code_verifier", "verifier",
                        "client_id", CLIENT_ID,
                        "client_secret", "secret")))
                .andRespond(withSuccess("{\"id_token\":\"" + idToken(c -> { }) + "\"}", MediaType.APPLICATION_JSON));

        provider.exchangeCode("the-code", "verifier", NONCE);
        server.verify();
    }

    @Test
    void unverifiedEmailIsReportedAsUnverified() throws Exception {
        expectTokenResponse(idToken(c -> c.claim("email_verified", false)));
        assertFalse(provider.exchangeCode("c", "v", NONCE).emailVerified());
    }

    @Test
    void missingEmailVerifiedClaimCountsAsUnverified() throws Exception {
        expectTokenResponse(idToken(c -> c.claim("email_verified", null)));
        assertFalse(provider.exchangeCode("c", "v", NONCE).emailVerified());
    }

    @Test
    void nonceMismatchIsRejected() throws Exception {
        expectTokenResponse(idToken(c -> c.claim("nonce", "replayed")));
        assertCode(OAuthErrorCode.INVALID_STATE, () -> provider.exchangeCode("c", "v", NONCE));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        expectTokenResponse(idToken(c -> c.audience("someone-else")));
        assertCode(OAuthErrorCode.PROVIDER_ERROR, () -> provider.exchangeCode("c", "v", NONCE));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        expectTokenResponse(idToken(c -> c.issuer("https://evil.example.com")));
        assertCode(OAuthErrorCode.PROVIDER_ERROR, () -> provider.exchangeCode("c", "v", NONCE));
    }

    @Test
    void expiredIdTokenIsRejected() throws Exception {
        expectTokenResponse(idToken(c -> c.expirationTime(new Date(System.currentTimeMillis() - 3_600_000))));
        assertCode(OAuthErrorCode.PROVIDER_ERROR, () -> provider.exchangeCode("c", "v", NONCE));
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair(); // sign with a key the decoder doesn't trust
        expectTokenResponse(idToken(c -> { }));
        assertCode(OAuthErrorCode.PROVIDER_ERROR, () -> provider.exchangeCode("c", "v", NONCE));
    }

    @Test
    void expiredAuthorizationCodeMapsToCodeExpired() {
        server.expect(requestTo(GoogleOAuthProvider.TOKEN_ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}"));
        assertCode(OAuthErrorCode.CODE_EXPIRED, () -> provider.exchangeCode("c", "v", NONCE));
    }

    @Test
    void googleOutageMapsToProviderError() {
        server.expect(requestTo(GoogleOAuthProvider.TOKEN_ENDPOINT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertCode(OAuthErrorCode.PROVIDER_ERROR, () -> provider.exchangeCode("c", "v", NONCE));
    }

    // ------------------------------------------------------------------------

    private void expectTokenResponse(String idToken) {
        server.expect(requestTo(GoogleOAuthProvider.TOKEN_ENDPOINT))
                .andRespond(withSuccess("{\"access_token\":\"ya29\",\"id_token\":\"" + idToken + "\"}",
                        MediaType.APPLICATION_JSON));
    }

    private String idToken(Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .subject("sub-42")
                .claim("email", "jane@example.com")
                .claim("email_verified", true)
                .claim("name", "Jane Doe")
                .claim("nonce", NONCE)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000));
        customize.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static void assertCode(OAuthErrorCode expected, org.junit.jupiter.api.function.Executable call) {
        OAuthException e = assertThrows(OAuthException.class, call);
        assertEquals(expected, e.getErrorCode());
    }
}
