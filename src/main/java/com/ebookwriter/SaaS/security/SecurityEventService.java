package com.ebookwriter.SaaS.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Structured audit logging for security-relevant events (failed logins,
 * lockouts, password resets, refresh-token reuse). Emits a single grep-able
 * line per event so they can be shipped to a log aggregator and alerted on.
 */
@Slf4j
@Service
public class SecurityEventService {

    public void log(SecurityEventType type,
                    String userIdentifier,
                    String ip,
                    String userAgent) {

        log.warn(
                "SECURITY_EVENT | type={} | user={} | ip={} | agent={}",
                type,
                userIdentifier,
                ip,
                userAgent
        );
    }
}
