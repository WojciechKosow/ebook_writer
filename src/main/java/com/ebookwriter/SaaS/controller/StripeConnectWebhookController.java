package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.service.store.StripeConnectWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stripe Connect webhook for creator-store payments. permitAll in
 * SecurityConfig — authenticated by its own Stripe signing secret. Independent of
 * the subscription webhook at /api/stripe/webhook.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StripeConnectWebhookController {

    private final StripeConnectWebhookService webhookService;

    @PostMapping("/api/stripe/connect/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.badRequest().body("Missing Stripe-Signature header");
        }
        try {
            webhookService.handle(payload, signature);
            return ResponseEntity.ok("ok");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            // A concurrent delivery of the same event recorded it first.
            log.info("[StripeConnect] Concurrent duplicate delivery — already handled");
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.error("[StripeConnect] Webhook processing failed — returning 500 so Stripe retries", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook processing failed");
        }
    }
}
