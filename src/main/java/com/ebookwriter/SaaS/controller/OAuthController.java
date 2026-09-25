package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.UserDTO;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.request.OAuthExchangeRequest;
import com.ebookwriter.SaaS.response.AuthResponse;
import com.ebookwriter.SaaS.security.AuthCookies;
import com.ebookwriter.SaaS.security.JwtProvider;
import com.ebookwriter.SaaS.security.RequestContextUtil;
import com.ebookwriter.SaaS.security.SecurityEventService;
import com.ebookwriter.SaaS.security.SecurityEventType;
import com.ebookwriter.SaaS.security.oauth.ExternalIdentity;
import com.ebookwriter.SaaS.security.oauth.OAuthErrorCode;
import com.ebookwriter.SaaS.security.oauth.OAuthException;
import com.ebookwriter.SaaS.security.oauth.OAuthProvider;
import com.ebookwriter.SaaS.security.oauth.OAuthProviderRegistry;
import com.ebookwriter.SaaS.security.oauth.OAuthStateService;
import com.ebookwriter.SaaS.service.RefreshTokenService;
import com.ebookwriter.SaaS.service.oauth.OAuthAccountService;
import com.ebookwriter.SaaS.service.oauth.OAuthLoginHandoffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * "Continue with Google" — server-side OAuth 2.0 / OpenID Connect login.
 *
 * <pre>
 *  frontend  ──GET /api/auth/oauth2/google/authorize?client_state=…──▶ backend
 *            (sets signed oauth2_state cookie; 302 → Google, scope openid email profile, PKCE)
 *  Google    ──GET /api/auth/oauth2/google/callback?code&amp;state──▶ backend
 *            (verify state cookie, exchange code, validate ID token + nonce,
 *             find/link/create the Scrivetta user, issue one-time login code)
 *            302 → {FRONTEND_URL}/auth/callback#code=…      (or ?error=&lt;code&gt;)
 *  frontend  ──POST /api/auth/oauth2/exchange {code, clientState}──▶ backend
 *            → same response as /api/auth/login (access token + refresh cookie)
 * </pre>
 *
 * Authorization codes, ID/access tokens and login codes are never logged.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/oauth2")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthProviderRegistry providers;
    private final OAuthStateService stateService;
    private final OAuthAccountService accountService;
    private final OAuthLoginHandoffService handoffService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookies authCookies;
    private final SecurityEventService securityEventService;
    private final RequestContextUtil requestContextUtil;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /** Step 1: start a login — redirect the browser to the provider. */
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider,
                                          @RequestParam(name = "client_state", required = false) String clientState) {
        try {
            OAuthProvider p = providers.require(provider);
            OAuthStateService.Started started = stateService.start(p.id(), clientState);
            URI target = p.authorizationUri(started.state(), started.nonce(), started.codeChallenge());

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(target)
                    .header(HttpHeaders.SET_COOKIE,
                            authCookies.oauthStateCookie(started.cookieValue(), OAuthStateService.TTL).toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        } catch (OAuthException e) {
            return failureRedirect(provider, e);
        }
    }

    /** Step 2: the provider redirects back here. Always ends in a redirect to the frontend. */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = AuthCookies.OAUTH_STATE_COOKIE, required = false)
                                         String stateCookie) {
        try {
            OAuthProvider p = providers.require(provider);

            if (error != null) {
                // access_denied = the user cancelled on the consent/account screen.
                throw new OAuthException(
                        "access_denied".equals(error) ? OAuthErrorCode.ACCESS_DENIED : OAuthErrorCode.PROVIDER_ERROR,
                        "Provider returned error=" + sanitize(error));
            }
            if (code == null || code.isBlank()) {
                throw new OAuthException(OAuthErrorCode.INVALID_REQUEST, "Callback without code");
            }

            OAuthStateService.Verified verified = stateService.verify(stateCookie, p.id(), state);
            ExternalIdentity identity = p.exchangeCode(code, verified.codeVerifier(), verified.nonce());
            User user = resolveUser(identity);
            String loginCode = handoffService.issue(user, verified.clientStateHash());

            URI target = URI.create(callbackBase() + "#code=" + loginCode);
            return redirect(target);
        } catch (OAuthException e) {
            return failureRedirect(provider, e);
        } catch (RuntimeException e) {
            log.error("[OAuth] provider={} callback failed unexpectedly: {}", sanitize(provider),
                    e.getClass().getSimpleName(), e);
            return failureRedirect(provider,
                    new OAuthException(OAuthErrorCode.SERVER_ERROR, "Unexpected callback failure", e));
        }
    }

    /** Step 3: the frontend trades the one-time code for a normal Scrivetta session. */
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@Valid @RequestBody OAuthExchangeRequest request) {
        User user;
        try {
            user = handoffService.redeem(request.getCode(), request.getClientState());
        } catch (OAuthException e) {
            log.warn("[OAuth] exchange rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "Your sign-in link has expired. Please try again.",
                    "error", e.getErrorCode().code()));
        }

        String accessToken = jwtProvider.generateToken(user.getEmail(), request.rememberMeOrDefault());
        String refreshToken = refreshTokenService.createRefreshToken(user, request.rememberMeOrDefault());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookies.refreshCookie(refreshToken,
                        Duration.ofDays(request.rememberMeOrDefault() ? 30 : 1)).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthResponse(accessToken, null, new UserDTO(
                        user.getId(), user.getDisplayName(), user.getEmail(), user.isEnabled())));
    }

    // ------------------------------------------------------------------------

    /** Two first-time logins racing on the same account hit a unique constraint; the retry finds the winner. */
    private User resolveUser(ExternalIdentity identity) {
        try {
            return accountService.resolveUser(identity);
        } catch (DataIntegrityViolationException e) {
            log.info("[OAuth] concurrent first login for provider={}, retrying resolution", identity.provider());
            return accountService.resolveUser(identity);
        }
    }

    private ResponseEntity<Void> failureRedirect(String provider, OAuthException e) {
        OAuthErrorCode code = e.getErrorCode();
        if (code == OAuthErrorCode.ACCESS_DENIED) {
            log.info("[OAuth] provider={} login cancelled by user", sanitize(provider));
        } else {
            log.warn("[OAuth] provider={} login failed: code={} reason={}",
                    sanitize(provider), code.code(), e.getMessage());
            securityEventService.log(SecurityEventType.OAUTH_LOGIN_FAILED, "oauth:" + code.code(),
                    requestContextUtil.getClientIp(), requestContextUtil.getUserAgent());
        }
        URI target = UriComponentsBuilder.fromUriString(callbackBase())
                .queryParam("error", code.code())
                .build()
                .toUri();
        return redirect(target);
    }

    /** Redirect to the frontend and clear the one-shot state cookie. */
    private ResponseEntity<Void> redirect(URI target) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .header(HttpHeaders.SET_COOKIE, authCookies.oauthStateCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                // The frontend URL carries the login code in its fragment; don't
                // leak even the path to third parties.
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private String callbackBase() {
        return frontendUrl.replaceAll("/+$", "") + "/auth/callback";
    }

    /** Provider-controlled strings go into logs — keep them short and single-line. */
    private static String sanitize(String value) {
        if (value == null) return "null";
        String cleaned = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
