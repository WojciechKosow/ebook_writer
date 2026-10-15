package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import com.ebookwriter.SaaS.entity.StripeWebhookEvent;
import com.ebookwriter.SaaS.repository.StripeWebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Accounts v2 status changes. Stripe delivers {@code v2.core.account...} events
 * as "thin" events (only the related object's id) through their own event
 * destination and signing secret. Whatever the event says, we re-fetch the
 * account from Stripe and store its real state — the same rule as on return
 * from onboarding. Deduplicated in {@code stripe_webhook_event}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeConnectAccountEventService {

    private final StripeConnectProperties properties;
    private final StripeWebhookEventRepository eventRepository;
    private final StoreService storeService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Transactional
    public void handle(String payload, String signatureHeader) {
        String secret = properties.getV2EventsSecret();
        if (secret == null || secret.isBlank()) {
            log.error("[StripeConnect] Cannot process v2 account event: stripe.connect.v2-events-secret is not set "
                    + "— set STRIPE_CONNECT_V2_EVENTS_SECRET");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook not configured");
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, secret, Webhook.DEFAULT_TOLERANCE);
        } catch (SignatureVerificationException e) {
            log.warn("[StripeConnect] v2 event signature verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature");
        }

        JsonNode event;
        try {
            event = MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload");
        }
        String id = string(event, "id");
        String type = string(event, "type");
        if (id == null || type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload");
        }
        if (eventRepository.existsById(id)) {
            log.info("[StripeConnect] Duplicate v2 event {} ({}) — skipping", id, type);
            return;
        }
        StripeWebhookEvent record = new StripeWebhookEvent();
        record.setEventId(id);
        record.setType(type);
        record.setProcessedAt(LocalDateTime.now());
        eventRepository.saveAndFlush(record);

        if (event.path("livemode").asBoolean(false) && !properties.isLiveEnabled()) {
            log.warn("[StripeConnect] Ignoring LIVE-mode v2 event {} ({}): stripe.connect.live-enabled is false", id, type);
            return;
        }
        JsonNode related = event.path("related_object");
        String relatedType = string(related, "type");
        String accountId = string(related, "id");
        if (!type.startsWith("v2.core.account") || !"v2.core.account".equals(relatedType) || accountId == null) {
            log.debug("[StripeConnect] Ignoring v2 event {} ({})", id, type);
            return;
        }
        try {
            storeService.syncAccount(accountId);
        } catch (StripeException e) {
            // Roll back (incl. the dedup row) so Stripe retries.
            throw new IllegalStateException("Could not fetch account " + accountId + ": " + e.getMessage(), e);
        }
    }

    private static String string(JsonNode o, String key) {
        JsonNode v = o == null ? null : o.get(key);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
