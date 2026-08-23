package com.ebookwriter.SaaS.security.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny in-memory fixed-window rate limiter. Good enough for a single app
 * instance (our deploy); if this service is ever scaled horizontally, back it
 * with a shared store (e.g. Redis) so limits are enforced across instances.
 */
@Component
public class RateLimiter {

    /** Above this many tracked keys, sweep stale windows to bound memory. */
    private static final int SWEEP_THRESHOLD = 50_000;
    /** Drop windows untouched for this long during a sweep. */
    private static final long MAX_IDLE_MS = 3_600_000L; // 1h

    private static final class Window {
        long startMs;
        int count;
        Window(long startMs) {
            this.startMs = startMs;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    /**
     * Count one hit against {@code key} and decide whether it is allowed.
     *
     * @return allowed=true if under {@code maxRequests} within the current window
     *         (the hit is counted); otherwise allowed=false with the seconds
     *         until the window resets.
     */
    public Decision check(String key, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        if (windows.size() > SWEEP_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMs >= MAX_IDLE_MS);
        }
        Window w = windows.computeIfAbsent(key, k -> new Window(now));
        synchronized (w) {
            if (now - w.startMs >= windowMs) {
                w.startMs = now;
                w.count = 0;
            }
            if (w.count >= maxRequests) {
                long retryAfter = Math.max(1, (windowMs - (now - w.startMs)) / 1000);
                return new Decision(false, retryAfter);
            }
            w.count++;
            return new Decision(true, 0);
        }
    }
}
