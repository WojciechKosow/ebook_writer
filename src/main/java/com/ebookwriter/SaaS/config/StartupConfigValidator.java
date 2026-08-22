package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.StripeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Fails fast (or loudly warns) at startup when security- or payment-critical
 * configuration is missing or left at an insecure development default.
 *
 * <p>Why this exists: the app ships with a committed dev JWT secret and empty
 * Stripe placeholders so local development works with zero setup. If that
 * config reaches production unchanged the consequences are severe:
 * <ul>
 *   <li>The dev JWT secret is public in the repo — anyone could forge an access
 *       token for any user (full account takeover, spending others' credits).</li>
 *   <li>An unset Stripe webhook secret makes <em>every</em> webhook fail
 *       signature verification, so paid orders never leave PENDING and the user
 *       is stuck on "your payment is processing" and never receives credits.</li>
 * </ul>
 *
 * <p>Set {@code app.security.require-strong-secrets=true} (env
 * {@code APP_SECURITY_REQUIRE_STRONG_SECRETS=true}) in every deployed
 * environment: it turns the warnings below into hard startup failures so a
 * misconfigured deploy never boots. It defaults to {@code false} so local dev
 * and the test suite keep working without extra setup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigValidator implements ApplicationRunner {

    /** Must match the committed default in application.yml. */
    static final String DEV_JWT_SECRET = "change-me-local-dev-secret-please-override-32b";
    private static final int MIN_JWT_SECRET_BYTES = 32; // HS256 needs a 256-bit key

    private final StripeProperties stripeProperties;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${app.security.require-strong-secrets:false}")
    private boolean requireStrongSecrets;

    @Override
    public void run(ApplicationArguments args) {
        validateJwtSecret();
        validateStripe();
    }

    private void validateJwtSecret() {
        int bytes = jwtSecret == null ? 0 : jwtSecret.getBytes(StandardCharsets.UTF_8).length;

        if (jwtSecret == null || jwtSecret.isBlank()) {
            fail("jwt.secret is not set — set a strong, random JWT_SECRET (>= 32 bytes).");
            return;
        }
        if (bytes < MIN_JWT_SECRET_BYTES) {
            // Too short for HS256 — Keys.hmacShaKeyFor would throw on first use
            // anyway, so stop now with a clear message rather than at login time.
            fail("jwt.secret is only " + bytes + " bytes — HS256 needs at least "
                    + MIN_JWT_SECRET_BYTES + ". Set a longer JWT_SECRET.");
            return;
        }
        if (DEV_JWT_SECRET.equals(jwtSecret)) {
            fail("jwt.secret is still the built-in development default, which is public in "
                    + "the repository — anyone could forge access tokens. Set a strong, random "
                    + "JWT_SECRET before exposing this service.");
        }
    }

    private void validateStripe() {
        if (isBlank(stripeProperties.getSecretKey())) {
            warnOrFail("stripe.secret-key is not set — all Stripe calls (checkout, "
                    + "subscriptions, billing portal) will fail. Set STRIPE_SECRET_KEY.");
        }
        if (isBlank(stripeProperties.getWebhookSecret())) {
            warnOrFail("stripe.webhook-secret is not set — every Stripe webhook will fail "
                    + "signature verification, so paid orders never get fulfilled and users are "
                    + "stuck on \"payment is processing\". Set STRIPE_WEBHOOK_SECRET to the signing "
                    + "secret of your webhook endpoint (whsec_...).");
        }
    }

    /**
     * A security-critical problem: fail the boot when strong secrets are
     * required (production), otherwise log a prominent warning (local dev).
     */
    private void fail(String message) {
        if (requireStrongSecrets) {
            throw new IllegalStateException("[Startup] Refusing to start: " + message);
        }
        log.warn("=================================================================");
        log.warn("[Startup] INSECURE CONFIG: {}", message);
        log.warn("[Startup] This is tolerated only because app.security.require-strong-secrets=false.");
        log.warn("[Startup] Set APP_SECURITY_REQUIRE_STRONG_SECRETS=true in every deployed environment.");
        log.warn("=================================================================");
    }

    /**
     * A payment-config problem: fail the boot when strong secrets are required,
     * otherwise warn. (Kept separate from {@link #fail} only for message clarity.)
     */
    private void warnOrFail(String message) {
        if (requireStrongSecrets) {
            throw new IllegalStateException("[Startup] Refusing to start: " + message);
        }
        log.warn("[Startup] PAYMENT CONFIG WARNING: {}", message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
