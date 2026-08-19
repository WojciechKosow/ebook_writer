package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.service.payment.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook. permitAll in SecurityConfig — authenticated by the Stripe
 * signature, not a JWT. Uses the RAW request body for signature verification.
 */
@RestController
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    @PostMapping("/api/stripe/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader("Stripe-Signature") String signature) {
        webhookService.handle(payload, signature);
        return ResponseEntity.ok("ok");
    }
}
