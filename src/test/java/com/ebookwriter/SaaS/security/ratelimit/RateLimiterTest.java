package com.ebookwriter.SaaS.security.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenBlocksWithRetryAfter() {
        RateLimiter limiter = new RateLimiter();
        String key = "ip|/api/auth/login";

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.check(key, 3, 60_000).allowed(), "hit " + i + " should be allowed");
        }
        RateLimiter.Decision blocked = limiter.check(key, 3, 60_000);
        assertFalse(blocked.allowed(), "the 4th hit must be blocked");
        assertTrue(blocked.retryAfterSeconds() >= 1, "a positive Retry-After must be reported");
    }

    @Test
    void differentKeysAreLimitedIndependently() {
        RateLimiter limiter = new RateLimiter();
        assertTrue(limiter.check("a", 1, 60_000).allowed(), "a: first allowed");
        assertFalse(limiter.check("a", 1, 60_000).allowed(), "a: second blocked");
        assertTrue(limiter.check("b", 1, 60_000).allowed(), "b is a separate bucket");
    }

    @Test
    void windowResetsAfterItElapses() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        String key = "k";
        long window = 20;

        assertTrue(limiter.check(key, 1, window).allowed());
        assertFalse(limiter.check(key, 1, window).allowed(), "second hit inside the window is blocked");

        Thread.sleep(window + 15);
        assertTrue(limiter.check(key, 1, window).allowed(), "after the window elapses it allows again");
    }
}
