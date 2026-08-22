package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.service.payment.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stripe webhook. permitAll in SecurityConfig — authenticated by the Stripe
 * signature, not a JWT. Uses the RAW request body for signature verification.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    @PostMapping("/api/stripe/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (signature == null || signature.isBlank()) {
            // No signature to verify — reject outright (never reaches fulfilment).
            return ResponseEntity.badRequest().body("Missing Stripe-Signature header");
        }
        try {
            webhookService.handle(payload, signature);
            return ResponseEntity.ok("ok");
        } catch (ResponseStatusException e) {
            // Deliberate, meaningful status (e.g. 400 invalid signature, 503 not
            // configured). Let it through unchanged; do NOT fall through to the
            // generic 400 handler that would leak internal detail.
            throw e;
        } catch (Exception e) {
            // Any unexpected fulfilment failure: the @Transactional handle() has
            // already rolled back, so nothing was granted. Return 500 so Stripe
            // retries on its schedule, and never leak the internal error to the
            // caller.
            log.error("[Stripe] Webhook processing failed — returning 500 so Stripe retries", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook processing failed");
        }
    }
}
