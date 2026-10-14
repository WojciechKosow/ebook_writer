package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import com.ebookwriter.SaaS.entity.Store;
import com.ebookwriter.SaaS.entity.StoreOrder;
import com.ebookwriter.SaaS.entity.StoreOrderStatus;
import com.ebookwriter.SaaS.entity.StoreProduct;
import com.ebookwriter.SaaS.entity.StripeWebhookEvent;
import com.ebookwriter.SaaS.repository.StoreOrderRepository;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.ebookwriter.SaaS.repository.StripeWebhookEventRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Stripe <b>Connect</b> webhook for creator-store payments — the only thing
 * that moves a store order past PENDING. Separate endpoint, signing secret and
 * dedup table from the platform's subscription webhook.
 *
 * <ul>
 *   <li>Signature-verified with {@code stripe.connect.webhook-secret}.</li>
 *   <li>Idempotent: the event id is recorded in {@code stripe_webhook_event}
 *       first, in the same transaction; a duplicate is a no-op.</li>
 *   <li>Every order event must come from the order's own connected account
 *       ({@code event.account}); a mismatch is logged and ignored.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeConnectWebhookService {

    private final StripeConnectProperties properties;
    private final StripeWebhookEventRepository eventRepository;
    private final StoreOrderRepository orderRepository;
    private final StoreProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final StoreService storeService;
    private final DownloadGrantService grantService;
    private final ApplicationEventPublisher publisher;

    /**
     * Verify, dedupe and process one delivery. Throws {@link ResponseStatusException}
     * for a bad signature (400) or missing configuration (503); any other failure
     * rolls the whole transaction back (including the dedup row) so Stripe retries.
     */
    @Transactional
    public void handle(String payload, String signatureHeader) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("[StripeConnect] Cannot process webhook: stripe.connect.webhook-secret is not set "
                    + "— set STRIPE_CONNECT_WEBHOOK_SECRET");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook not configured");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, secret);
        } catch (SignatureVerificationException e) {
            log.warn("[StripeConnect] Webhook signature verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature");
        }

        if (eventRepository.existsById(event.getId())) {
            log.info("[StripeConnect] Duplicate event {} ({}) — skipping", event.getId(), event.getType());
            return;
        }
        // Record first: a concurrent duplicate collides on the PK here (the
        // controller answers it 200), and a failure below rolls this row back too.
        StripeWebhookEvent record = new StripeWebhookEvent();
        record.setEventId(event.getId());
        record.setType(event.getType());
        record.setProcessedAt(LocalDateTime.now());
        eventRepository.saveAndFlush(record);

        if (Boolean.TRUE.equals(event.getLivemode()) && !properties.isLiveEnabled()) {
            log.warn("[StripeConnect] Ignoring LIVE-mode event {} ({}): stripe.connect.live-enabled is false",
                    event.getId(), event.getType());
            return;
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCompleted(event);
            case "checkout.session.expired" -> handleExpired(event);
            case "account.updated" -> handleAccountUpdated(event);
            case "charge.refunded" -> handleRefunded(event);
            default -> log.debug("[StripeConnect] Ignoring event type {}", event.getType());
        }
    }

    private void handleCompleted(Event event) {
        if (!(extractObject(event) instanceof Session session)) {
            return;
        }
        StoreOrder order = resolveOrder(event, session);
        if (order == null) {
            return;
        }
        if (!"paid".equals(session.getPaymentStatus())) {
            // Cards only, so this should not happen; never deliver without payment.
            log.warn("[StripeConnect] Session {} completed with payment_status={} — order {} left {}",
                    session.getId(), session.getPaymentStatus(), order.getId(), order.getStatus());
            return;
        }
        if (order.getStatus() == StoreOrderStatus.PAID || order.getStatus() == StoreOrderStatus.REFUNDED) {
            log.info("[StripeConnect] Order {} already {} — skipping", order.getId(), order.getStatus());
            return;
        }
        if (!Objects.equals(session.getAmountTotal(), order.getAmount())
                || !order.getCurrency().equalsIgnoreCase(String.valueOf(session.getCurrency()))) {
            log.error("[StripeConnect] Session {} amount {} {} does not match order {} ({} {}) — ignoring",
                    session.getId(), session.getAmountTotal(), session.getCurrency(),
                    order.getId(), order.getAmount(), order.getCurrency());
            return;
        }

        String email = session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null;
        if (email == null) {
            email = session.getCustomerEmail();
        }
        order.setBuyerEmail(email);
        order.setPaymentIntentId(session.getPaymentIntent());
        if (session.getConsent() != null && "accepted".equals(session.getConsent().getTermsOfService())) {
            // Stripe records acceptance at completion; the event's creation time is that moment.
            order.setConsentAcceptedAt(toLocal(event.getCreated()));
        } else {
            log.warn("[StripeConnect] Session {} completed without recorded terms consent", session.getId());
        }
        order.setStatus(StoreOrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.saveAndFlush(order);

        String token = grantService.issue(order.getId());
        String productTitle = productRepository.findById(order.getProductId()).map(StoreProduct::getTitle).orElse(null);
        String storeName = storeRepository.findById(order.getStoreId()).map(Store::getName).orElse(null);
        publisher.publishEvent(new StorePurchaseCompletedEvent(order.getId(), email, productTitle, storeName, token));
        log.info("[StripeConnect] Order {} PAID (session {}, account {})",
                order.getId(), session.getId(), order.getStripeAccountId());
    }

    private void handleExpired(Event event) {
        if (!(extractObject(event) instanceof Session session)) {
            return;
        }
        StoreOrder order = resolveOrder(event, session);
        if (order == null || order.getStatus() != StoreOrderStatus.PENDING) {
            return;
        }
        order.setStatus(StoreOrderStatus.EXPIRED);
        orderRepository.save(order);
        log.info("[StripeConnect] Order {} EXPIRED", order.getId());
    }

    private void handleAccountUpdated(Event event) {
        String accountId = event.getAccount();
        if (extractObject(event) instanceof Account account && accountId != null
                && !accountId.equals(account.getId())) {
            log.warn("[StripeConnect] account.updated: event.account {} != object {} — ignoring",
                    accountId, account.getId());
            return;
        }
        if (accountId == null) {
            log.warn("[StripeConnect] account.updated without event.account — ignoring");
            return;
        }
        try {
            storeService.syncAccount(accountId);
        } catch (StripeException e) {
            // Let Stripe retry: the status must come from a server-side fetch.
            throw new IllegalStateException("Could not fetch account " + accountId + ": " + e.getMessage(), e);
        }
    }

    private void handleRefunded(Event event) {
        if (!(extractObject(event) instanceof Charge charge)) {
            return;
        }
        String paymentIntentId = charge.getPaymentIntent();
        if (paymentIntentId == null) {
            return;
        }
        Optional<StoreOrder> found = orderRepository.findByPaymentIntentId(paymentIntentId);
        if (found.isEmpty()) {
            log.warn("[StripeConnect] charge.refunded for unknown payment_intent {} — ignoring", paymentIntentId);
            return;
        }
        StoreOrder order = found.get();
        if (!Objects.equals(event.getAccount(), order.getStripeAccountId())) {
            log.warn("[StripeConnect] charge.refunded from account {} for order {} on account {} — ignoring",
                    event.getAccount(), order.getId(), order.getStripeAccountId());
            return;
        }
        long amount = charge.getAmount() != null ? charge.getAmount() : 0L;
        long refunded = charge.getAmountRefunded() != null ? charge.getAmountRefunded() : 0L;
        boolean full = Boolean.TRUE.equals(charge.getRefunded()) || (amount > 0 && refunded >= amount);
        if (!full) {
            log.info("[StripeConnect] Partial refund on order {} ({} of {}) — access kept", order.getId(), refunded, amount);
            return;
        }
        if (order.getStatus() != StoreOrderStatus.PAID) {
            log.info("[StripeConnect] Full refund on order {} in status {} — nothing to change",
                    order.getId(), order.getStatus());
            return;
        }
        order.setStatus(StoreOrderStatus.REFUNDED);
        orderRepository.save(order);
        grantService.revoke(order.getId());
        log.info("[StripeConnect] Order {} REFUNDED — download access revoked", order.getId());
    }

    /**
     * The order a Checkout Session belongs to, from our metadata — or null (logged)
     * when it is unknown or the event did not come from the order's connected account.
     */
    private StoreOrder resolveOrder(Event event, Session session) {
        String ref = session.getMetadata() != null ? session.getMetadata().get("order_id") : null;
        if (ref == null) {
            ref = session.getClientReferenceId();
        }
        UUID orderId;
        try {
            orderId = ref != null ? UUID.fromString(ref) : null;
        } catch (IllegalArgumentException e) {
            orderId = null;
        }
        StoreOrder order = orderId != null ? orderRepository.findById(orderId).orElse(null) : null;
        if (order == null) {
            log.warn("[StripeConnect] {} for session {}: no store order '{}' — ignoring",
                    event.getType(), session.getId(), ref);
            return null;
        }
        if (!Objects.equals(event.getAccount(), order.getStripeAccountId())) {
            log.warn("[StripeConnect] {} from account {} for order {} on account {} — mismatch, ignoring",
                    event.getType(), event.getAccount(), order.getId(), order.getStripeAccountId());
            return null;
        }
        if (order.getCheckoutSessionId() != null && !order.getCheckoutSessionId().equals(session.getId())) {
            log.warn("[StripeConnect] {}: session {} is not order {}'s session {} — ignoring",
                    event.getType(), session.getId(), order.getId(), order.getCheckoutSessionId());
            return null;
        }
        return order;
    }

    private StripeObject extractObject(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isPresent()) {
            return obj.get();
        }
        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            log.warn("[StripeConnect] Could not deserialize event {} ({}): {}",
                    event.getId(), event.getType(), e.getMessage());
            return null;
        }
    }

    private static LocalDateTime toLocal(Long epochSeconds) {
        return epochSeconds == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }
}
