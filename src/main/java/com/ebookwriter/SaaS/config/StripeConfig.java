package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.StripeProperties;
import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Sets the global Stripe API key at startup so every Stripe call is
 * authenticated. Left unset the app still boots — only payment calls fail.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StripeConfig {

    private final StripeProperties stripeProperties;

    @PostConstruct
    void init() {
        String key = stripeProperties.getSecretKey();
        if (key == null || key.isBlank()) {
            log.warn("[Stripe] stripe.secret-key is not configured — payments will fail until it is set");
        }
        Stripe.apiKey = key;
    }
}
