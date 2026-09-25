package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserIdentityRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.security.oauth.ExternalIdentity;
import com.ebookwriter.SaaS.security.oauth.OAuthErrorCode;
import com.ebookwriter.SaaS.security.oauth.OAuthException;
import com.ebookwriter.SaaS.security.oauth.OAuthProvider;
import com.ebookwriter.SaaS.service.MailService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end "Continue with Google" flow through the real controller, state
 * cookie, account linking, one-time login code and session issuing — with the
 * provider's network leg replaced by {@link FakeProvider}. (Google's own ID-token
 * validation is covered by GoogleOAuthProviderTest.)
 */
@SpringBootTest
class OAuthFlowIntegrationTest {

    private static final String FRONTEND_CALLBACK = "http://localhost:5173/auth/callback";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired UserIdentityRepository identityRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired FakeProvider fake;

    @MockitoBean MailService mailService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---- account creation / idempotency ------------------------------------

    @Test
    void googleSignUpCreatesActivatedUserAndReturnsSession() throws Exception {
        String email = unique("new");
        fake.next(identity("sub-" + email, email, true, "New Person"));

        Session s = fullLogin();

        assertNotNull(s.accessToken);
        User user = userRepository.findByEmail(email).orElseThrow();
        assertTrue(user.isEnabled(), "Google-verified email activates the account");
        assertEquals("New Person", user.getDisplayName());
        assertEquals(user.getId().toString(), s.userId);
        assertTrue(identityRepository.findByProviderAndProviderUserId("fake", "sub-" + email).isPresent());

        // The issued access token works on protected endpoints.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + s.accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedGoogleLoginNeverCreatesDuplicates() throws Exception {
        String email = unique("repeat");
        fake.next(identity("sub-" + email, email, true, "Rep"));
        Session first = fullLogin();
        fake.next(identity("sub-" + email, email, true, "Rep"));
        Session second = fullLogin();

        assertEquals(first.userId, second.userId);
        assertEquals(1, userRepository.findAll().stream().filter(u -> u.getEmail().equals(email)).count());
        assertEquals(1, identityRepository.findAll().stream()
                .filter(i -> i.getProviderUserId().equals("sub-" + email)).count());
    }

    @Test
    void linkedIdentityWinsEvenIfGoogleEmailChanged() throws Exception {
        String email = unique("moved");
        fake.next(identity("sub-" + email, email, true, "M"));
        Session first = fullLogin();

        fake.next(identity("sub-" + email, unique("renamed"), false, "M"));
        Session second = fullLogin();

        assertEquals(first.userId, second.userId);
    }

    // ---- existing email/password accounts -----------------------------------

    @Test
    void existingVerifiedPasswordAccountIsLinkedAndBothMethodsWork() throws Exception {
        String email = unique("existing");
        User existing = saveUser(email, "Password1!", true);

        fake.next(identity("sub-" + email, email.toUpperCase(), true, "Other Name"));
        Session s = fullLogin();

        assertEquals(existing.getId().toString(), s.userId, "same canonical account, no duplicate");
        assertEquals("Existing", userRepository.findById(existing.getId()).orElseThrow().getDisplayName());

        // Email + password still signs in to the same user.
        passwordLogin(email, "Password1!", 200);
    }

    @Test
    void unverifiedPreRegisteredAccountIsSecuredOnLink() throws Exception {
        String email = unique("squatted");
        User squatted = saveUser(email, "AttackerPass1!", false);

        fake.next(identity("sub-" + email, email, true, "Owner"));
        Session s = fullLogin();

        assertEquals(squatted.getId().toString(), s.userId);
        assertTrue(userRepository.findById(squatted.getId()).orElseThrow().isEnabled());
        // The password chosen before email ownership was proven no longer works.
        passwordLogin(email, "AttackerPass1!", 400);
    }

    @Test
    void unverifiedGoogleEmailNeverCreatesOrLinks() throws Exception {
        String email = unique("unverified");
        User existing = saveUser(email, "Password1!", true);
        fake.next(identity("sub-" + email, email, false, "X"));

        assertEquals("email_not_verified", callbackError(startAndCallback()));
        assertTrue(identityRepository.findByUserAndProvider(existing, "fake").isEmpty());

        String fresh = unique("unverified-new");
        fake.next(identity("sub-" + fresh, fresh, false, "X"));
        assertEquals("email_not_verified", callbackError(startAndCallback()));
        assertTrue(userRepository.findByEmail(fresh).isEmpty());
    }

    @Test
    void accountLinkedToDifferentGoogleAccountIsNotHijacked() throws Exception {
        String email = unique("conflict");
        fake.next(identity("sub-A-" + email, email, true, "A"));
        fullLogin();

        fake.next(identity("sub-B-" + email, email, true, "B"));
        assertEquals("account_conflict", callbackError(startAndCallback()));
    }

    // ---- protocol / error handling ------------------------------------------

    @Test
    void authorizeSetsHardenedStateCookieAndRedirectsToProvider() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/auth/oauth2/fake/authorize")
                        .param("client_state", clientState()))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        assertTrue(res.getRedirectedUrl().startsWith("https://idp.test/auth"));
        String setCookie = res.getHeader("Set-Cookie");
        assertTrue(setCookie.startsWith("oauth2_state="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Path=/api/auth/oauth2"));
    }

    @Test
    void invalidStateIsRejected() throws Exception {
        fake.next(identity("sub-x", unique("state"), true, "S"));
        Started started = start();

        MockHttpServletResponse res = mvc.perform(get("/api/auth/oauth2/fake/callback")
                        .param("code", "abc").param("state", "forged")
                        .cookie(started.cookie))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertEquals("invalid_state", callbackError(res));

        // No state cookie at all (e.g. callback opened in another browser).
        res = mvc.perform(get("/api/auth/oauth2/fake/callback")
                        .param("code", "abc").param("state", started.state))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertEquals("invalid_state", callbackError(res));
    }

    @Test
    void userCancellingAtGoogleIsHandled() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/auth/oauth2/fake/callback")
                        .param("error", "access_denied").param("state", "s"))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertEquals("access_denied", callbackError(res));
    }

    @Test
    void callbackWithoutCodeIsRejected() throws Exception {
        Started started = start();
        MockHttpServletResponse res = mvc.perform(get("/api/auth/oauth2/fake/callback")
                        .param("state", started.state).cookie(started.cookie))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertEquals("invalid_request", callbackError(res));
    }

    @Test
    void providerFailureIsHandled() throws Exception {
        fake.fail(new OAuthException(OAuthErrorCode.CODE_EXPIRED, "invalid_grant"));
        assertEquals("code_expired", callbackError(startAndCallback()));
    }

    @Test
    void unconfiguredGoogleFailsCleanly() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/auth/oauth2/google/authorize")
                        .param("client_state", clientState()))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertEquals("not_configured", callbackError(res));
    }

    @Test
    void loginCodeIsSingleUseAndBoundToTheClient() throws Exception {
        String email = unique("handoff");
        fake.next(identity("sub-" + email, email, true, "H"));
        Started started = start();
        String code = loginCode(callback(started));

        // Wrong clientState (code injected into another browser) → rejected.
        exchange(code, clientState(), 401);
        // Right clientState → session.
        exchange(code, started.clientState, 200);
        // Replay → rejected.
        exchange(code, started.clientState, 401);
    }

    @Test
    void garbageLoginCodeIsRejected() throws Exception {
        exchange("not-a-code", clientState(), 401);
        exchange(UUID.randomUUID() + ".secret", clientState(), 401);
    }

    // ---- existing auth is untouched -----------------------------------------

    @Test
    void passwordRegistrationAndLoginStillWork() throws Exception {
        String email = unique("classic");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Classic\",\"email\":\"" + email + "\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk());
        User user = userRepository.findByEmail(email).orElseThrow();
        assertFalse(user.isEnabled(), "email verification still required for password sign-ups");
        passwordLogin(email, "Password1!", 400);

        user.setEnabled(true);
        userRepository.save(user);
        passwordLogin(email, "Password1!", 200);
    }

    @Test
    void protectedEndpointsRejectAnonymousUsers() throws Exception {
        int status = mvc.perform(get("/api/ebooks")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "anonymous access must be refused, got " + status);
    }

    // ------------------------------------------------------------------------

    private record Started(String state, Cookie cookie, String clientState) {
    }

    private record Session(String accessToken, String userId) {
    }

    private Started start() throws Exception {
        String clientState = clientState();
        MockHttpServletResponse res = mvc.perform(get("/api/auth/oauth2/fake/authorize")
                        .param("client_state", clientState))
                .andExpect(status().isFound()).andReturn().getResponse();
        String state = UriComponentsBuilder.fromUriString(res.getRedirectedUrl()).build()
                .getQueryParams().getFirst("state");
        return new Started(state, res.getCookie("oauth2_state"), clientState);
    }

    private MockHttpServletResponse callback(Started started) throws Exception {
        return mvc.perform(get("/api/auth/oauth2/fake/callback")
                        .param("code", "provider-code").param("state", started.state)
                        .cookie(started.cookie))
                .andExpect(status().isFound()).andReturn().getResponse();
    }

    private MockHttpServletResponse startAndCallback() throws Exception {
        return callback(start());
    }

    private Session fullLogin() throws Exception {
        Started started = start();
        MockHttpServletResponse cb = callback(started);
        String code = loginCode(cb);
        MockHttpServletResponse res = exchange(code, started.clientState, 200);
        String setCookie = res.getHeader("Set-Cookie");
        assertTrue(setCookie.startsWith("refreshToken=") && setCookie.contains("HttpOnly"),
                "refresh token goes only into the httpOnly cookie");
        String body = res.getContentAsString();
        assertFalse(body.contains("\"refreshToken\":\"") , "refresh token must not be in the body");
        return new Session(jsonField(body, "token"), jsonField(body, "id"));
    }

    private MockHttpServletResponse exchange(String code, String clientState, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/auth/oauth2/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"clientState\":\"" + clientState + "\",\"rememberMe\":true}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse();
    }

    private void passwordLogin(String email, String password, int expectedStatus) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"rememberMe\":false}"))
                .andExpect(status().is(expectedStatus));
    }

    private static String loginCode(MockHttpServletResponse callback) {
        String location = callback.getRedirectedUrl();
        assertNotNull(location);
        assertTrue(location.startsWith(FRONTEND_CALLBACK + "#code="), "unexpected redirect: " + location);
        return location.substring((FRONTEND_CALLBACK + "#code=").length());
    }

    private static String callbackError(MockHttpServletResponse res) {
        URI uri = URI.create(res.getRedirectedUrl());
        assertTrue(res.getRedirectedUrl().startsWith(FRONTEND_CALLBACK + "?error="),
                "unexpected redirect: " + uri);
        assertFalse(res.getRedirectedUrl().contains("#code="));
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("error");
    }

    private User saveUser(String email, String password, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setDisplayName("Existing");
        u.setPassword(passwordEncoder.encode(password));
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    private static ExternalIdentity identity(String sub, String email, boolean verified, String name) {
        return new ExternalIdentity("fake", sub, email, verified, name, null);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String clientState() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String jsonField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), field + " missing in " + json);
        return m.group(1);
    }

    /** Stands in for Google: checks the nonce round-trip, returns a scripted identity. */
    static class FakeProvider implements OAuthProvider {
        private final AtomicReference<ExternalIdentity> next = new AtomicReference<>();
        private final AtomicReference<OAuthException> failure = new AtomicReference<>();
        private final Map<String, String> nonceByState = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicReference<String> lastNonce = new AtomicReference<>();

        void next(ExternalIdentity identity) {
            failure.set(null);
            next.set(identity);
        }

        void fail(OAuthException e) {
            failure.set(e);
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public URI authorizationUri(String state, String nonce, String codeChallenge) {
            nonceByState.put(state, nonce);
            lastNonce.set(nonce);
            return URI.create("https://idp.test/auth?state=" + state + "&code_challenge=" + codeChallenge);
        }

        @Override
        public ExternalIdentity exchangeCode(String code, String codeVerifier, String expectedNonce) {
            if (failure.get() != null) throw failure.getAndSet(null);
            assertTrue(nonceByState.containsValue(expectedNonce), "nonce must round-trip via the state cookie");
            assertNotNull(codeVerifier);
            return next.get();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeProvider fakeProvider() {
            return new FakeProvider();
        }
    }
}
