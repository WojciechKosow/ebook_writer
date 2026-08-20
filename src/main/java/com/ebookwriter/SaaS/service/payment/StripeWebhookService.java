package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.StripeProperties;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.ebookwriter.SaaS.repository.ProcessedStripeEventRepository;
import com.ebookwriter.SaaS.repository.UserSubscriptionRepository;
import com.ebookwriter.SaaS.service.credit.CreditService;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Source of truth for payment fulfilment. Stripe calls the webhook
 * server-to-server, so credits are granted regardless of whether the user's
 * browser survives the redirect. Double-grants are prevented by three layers:
 *   1. Signature verification (only Stripe can trigger this).
 *   2. Event dedup on the Stripe event id (ProcessedStripeEvent PK).
 *   3. Idempotent fulfilment: a PaymentOrder flips to PAID exactly once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final StripeProperties stripeProperties;
    private final CreditProperties creditProperties;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final CreditService creditService;

    @Transactional
    public void handle(String payload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("[Stripe] Webhook signature verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature");
        }

        if (processedStripeEventRepository.existsById(event.getId())) {
            log.info("[Stripe] Duplicate event {} ({}) — skipping", event.getId(), event.getType());
            return;
        }

        switch (event.getType()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    handleCheckoutCompleted(extractSession(event));
            case "checkout.session.expired" -> updateOrderStatus(extractSession(event), PaymentOrderStatus.EXPIRED);
            case "checkout.session.async_payment_failed" -> updateOrderStatus(extractSession(event), PaymentOrderStatus.FAILED);

            // Subscription credits (both the first month and renewals) are
            // granted here — every successful subscription payment produces an
            // invoice, which is the reliable "money received" signal.
            case "invoice.paid" -> handleInvoicePaid(event);

            case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);

            default -> log.debug("[Stripe] Ignoring event type {}", event.getType());
        }

        ProcessedStripeEvent processed = new ProcessedStripeEvent();
        processed.setEventId(event.getId());
        processed.setType(event.getType());
        processed.setReceivedAt(LocalDateTime.now());
        processedStripeEventRepository.save(processed);
    }

    private void handleCheckoutCompleted(Session session) {
        if (session == null) {
            return;
        }
        PaymentOrder order = resolveOrder(session);
        if (order == null) {
            log.warn("[Stripe] No order for session {} — cannot fulfil", session.getId());
            return;
        }
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            log.info("[Stripe] Order {} already PAID — skipping", order.getId());
            return;
        }

        order.setStripePaymentIntentId(session.getPaymentIntent());

        switch (order.getPurpose()) {
            case CREDIT_PACK -> {
                // One-time payment: require the money to be in before granting.
                if (!"paid".equals(session.getPaymentStatus())) {
                    log.info("[Stripe] Pack order {} not paid yet (payment_status={}) — awaiting payment",
                            order.getId(), session.getPaymentStatus());
                    return; // leave PENDING; an async event will settle it
                }
                creditService.grant(order.getUserId(), order.getCreditsGranted(),
                        CreditTransactionType.CREDIT_PURCHASE, null, order.getId(),
                        "Purchased " + (order.getCreditPack() != null ? order.getCreditPack().getDisplayName() : "credits"));
            }
            case SUBSCRIPTION -> {
                // Activate the subscription here; the CREDITS are granted by
                // invoice.paid (subscription_create for the first month), so the
                // grant never depends on the checkout session's payment_status.
                String subscriptionId = session.getSubscription();
                order.setStripeSubscriptionId(subscriptionId);
                if (subscriptionId != null) {
                    upsertSubscription(order.getUserId(), subscriptionId, "active", false);
                }
            }
        }

        order.setStatus(PaymentOrderStatus.PAID);
        order.setFulfilledAt(LocalDateTime.now());
        paymentOrderRepository.save(order);
        log.info("[Stripe] Fulfilled order {} ({}) for user {}",
                order.getId(), order.getPurpose(), order.getUserId());
    }

    /**
     * Grant subscription credits on every paid invoice — the first one
     * (billing_reason "subscription_create") and every renewal
     * ("subscription_cycle"). Deduped by the Stripe event id, so one grant per
     * successful invoice.
     */
    private void handleInvoicePaid(Event event) {
        if (!(extractObject(event) instanceof Invoice invoice)) {
            return;
        }
        String reason = invoice.getBillingReason();
        boolean subscriptionInvoice =
                "subscription_create".equals(reason) || "subscription_cycle".equals(reason);
        if (!subscriptionInvoice) {
            log.debug("[Stripe] invoice.paid billing_reason={} — not a subscription invoice, skipping", reason);
            return;
        }
        String subscriptionId = subscriptionIdOf(invoice);
        if (subscriptionId == null) {
            log.warn("[Stripe] invoice.paid without a subscription id — skipping");
            return;
        }
        UUID userId = resolveSubscriptionUser(subscriptionId);
        if (userId == null) {
            log.warn("[Stripe] invoice.paid for unresolved subscription {} — skipping", subscriptionId);
            return;
        }
        creditService.grant(userId, creditProperties.getSubscriptionMonthlyGrant(),
                CreditTransactionType.SUBSCRIPTION_GRANT, null, null,
                "subscription_create".equals(reason)
                        ? "Subscription — monthly credits"
                        : "Subscription renewal — monthly credits");
        log.info("[Stripe] Granted subscription credits ({}) to user {} for subscription {}",
                reason, userId, subscriptionId);
    }

    /**
     * Resolve the user for a subscription. Uses the stored mapping, falling back
     * to the subscription's own metadata (set at checkout) when invoice.paid
     * races ahead of checkout.session.completed.
     */
    private UUID resolveSubscriptionUser(String subscriptionId) {
        Optional<UserSubscription> mapping = userSubscriptionRepository.findByStripeSubscriptionId(subscriptionId);
        if (mapping.isPresent()) {
            UserSubscription sub = mapping.get();
            if (!"active".equals(sub.getStatus())) {
                sub.setStatus("active");
                userSubscriptionRepository.save(sub);
            }
            return sub.getUserId();
        }
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            String uid = subscription.getMetadata() != null ? subscription.getMetadata().get("userId") : null;
            if (uid != null && !uid.isBlank()) {
                UUID userId = UUID.fromString(uid);
                upsertSubscription(userId, subscriptionId,
                        subscription.getStatus() != null ? subscription.getStatus() : "active",
                        Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
                return userId;
            }
        } catch (StripeException e) {
            log.warn("[Stripe] Could not retrieve subscription {} to resolve user: {}", subscriptionId, e.getMessage());
        }
        return null;
    }

    private void handleSubscriptionUpdated(Event event) {
        if (!(extractObject(event) instanceof Subscription subscription)) {
            return;
        }
        userSubscriptionRepository.findByStripeSubscriptionId(subscription.getId()).ifPresent(sub -> {
            sub.setStatus(subscription.getStatus());
            sub.setCancelAtPeriodEnd(Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
            userSubscriptionRepository.save(sub);
            log.info("[Stripe] Subscription {} updated → status={} cancelAtPeriodEnd={}",
                    subscription.getId(), subscription.getStatus(), subscription.getCancelAtPeriodEnd());
        });
    }

    private void handleSubscriptionDeleted(Event event) {
        if (!(extractObject(event) instanceof Subscription subscription)) {
            return;
        }
        userSubscriptionRepository.findByStripeSubscriptionId(subscription.getId()).ifPresent(sub -> {
            sub.setStatus("canceled");
            sub.setCancelAtPeriodEnd(false);
            userSubscriptionRepository.save(sub);
            log.info("[Stripe] Subscription {} canceled", subscription.getId());
        });
    }

    // ------------------------------------------------------------------------

    private void upsertSubscription(UUID userId, String subscriptionId, String status, boolean cancelAtPeriodEnd) {
        UserSubscription sub = userSubscriptionRepository.findById(userId)
                .orElseGet(() -> UserSubscription.builder().userId(userId).build());
        sub.setStripeSubscriptionId(subscriptionId);
        sub.setStatus(status);
        sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        userSubscriptionRepository.save(sub);
    }

    private String subscriptionIdOf(Invoice invoice) {
        Invoice.Parent parent = invoice.getParent();
        if (parent != null && parent.getSubscriptionDetails() != null) {
            return parent.getSubscriptionDetails().getSubscription();
        }
        return null;
    }

    private void updateOrderStatus(Session session, PaymentOrderStatus status) {
        PaymentOrder order = resolveOrder(session);
        if (order == null || order.getStatus() == PaymentOrderStatus.PAID) {
            return;
        }
        order.setStatus(status);
        paymentOrderRepository.save(order);
        log.info("[Stripe] Order {} → {}", order.getId(), status);
    }

    private PaymentOrder resolveOrder(Session session) {
        if (session == null) {
            return null;
        }
        String ref = session.getClientReferenceId();
        if (ref == null && session.getMetadata() != null) {
            ref = session.getMetadata().get("orderId");
        }
        if (ref != null) {
            try {
                return paymentOrderRepository.findById(UUID.fromString(ref)).orElse(null);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return paymentOrderRepository.findByStripeCheckoutSessionId(session.getId()).orElse(null);
    }

    private Session extractSession(Event event) {
        return extractObject(event) instanceof Session session ? session : null;
    }

    private StripeObject extractObject(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isPresent()) {
            return obj.get();
        }
        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            log.warn("[Stripe] Could not deserialize event {} ({}): {}",
                    event.getId(), event.getType(), e.getMessage());
            return null;
        }
    }
}
