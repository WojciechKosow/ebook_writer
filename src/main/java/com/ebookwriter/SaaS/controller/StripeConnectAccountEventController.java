package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.service.store.StripeConnectAccountEventService;
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
 * Event destination for Accounts v2 events ({@code v2.core.account...}) of the
 * creator stores' connected accounts. permitAll in SecurityConfig — verified by
 * its own signing secret.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StripeConnectAccountEventController {

    private final StripeConnectAccountEventService eventService;

    @PostMapping("/api/stripe/connect/account-events")
    public ResponseEntity<String> events(@RequestBody String payload,
                                         @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.badRequest().body("Missing Stripe-Signature header");
        }
        try {
            eventService.handle(payload, signature);
            return ResponseEntity.ok("ok");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            log.info("[StripeConnect] Concurrent duplicate v2 event — already handled");
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.error("[StripeConnect] v2 account event failed — returning 500 so Stripe retries", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Event processing failed");
        }
    }
}
