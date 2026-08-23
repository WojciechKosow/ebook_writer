package com.ebookwriter.SaaS.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Per-IP rate limiting for the sensitive, unauthenticated auth endpoints.
 * Login already has account lockout; this adds an IP-based cap that also blunts
 * abuse of the email-sending endpoints (registration, resend-verification,
 * forgot-password) — e.g. spamming verification/reset mail at arbitrary
 * addresses and burning the mail provider's quota/reputation.
 *
 * <p>Over the limit → 429 with a Retry-After header. Registered early (before
 * the security filter chain) via {@link RateLimitConfig}, and scoped to
 * {@code /api/auth/*}, so it never touches the Stripe webhook or app traffic.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    /** method + exact path → max hits per window. */
    private record Rule(String method, String path, int maxRequests, long windowMs) {}

    private static final long MIN = 60_000L;
    private final List<Rule> rules = List.of(
            new Rule("POST", "/api/auth/login", 10, 5 * MIN),
            new Rule("POST", "/api/auth/register", 5, 15 * MIN),
            new Rule("POST", "/api/auth/forgot-password", 5, 15 * MIN),
            new Rule("POST", "/api/auth/resend-verification-email", 5, 15 * MIN),
            new Rule("POST", "/api/auth/reset-password", 10, 15 * MIN)
    );

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Rule rule = matchRule(request);
        if (rule != null) {
            String key = rule.path() + "|" + clientIp(request);
            RateLimiter.Decision decision = limiter.check(key, rule.maxRequests(), rule.windowMs());
            if (!decision.allowed()) {
                log.warn("[RateLimit] {} {} from {} exceeded {}/{}s — 429",
                        rule.method(), rule.path(), clientIp(request), rule.maxRequests(), rule.windowMs() / 1000);
                response.setStatus(429); // Too Many Requests
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Too many requests. Please try again later.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Rule matchRule(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        for (Rule r : rules) {
            if (r.method().equals(method) && r.path().equals(uri)) {
                return r;
            }
        }
        return null;
    }

    /** Same X-Forwarded-For-first resolution the rest of the app uses. */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }
}
