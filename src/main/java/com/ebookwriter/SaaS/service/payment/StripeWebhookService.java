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
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                Session session = extractSession(event);
                if (session != null && "paid".equals(session.getPaymentStatus())) {
                    fulfill(session);
                } else if (session != null) {
                    log.info("[Stripe] Session {} completed but payment_status={} — awaiting payment",
                            session.getId(), session.getPaymentStatus());
                }
            }
            case "checkout.session.expired" -> updateOrderStatus(extractSession(event), PaymentOrderStatus.EXPIRED);
            case "checkout.session.async_payment_failed" -> updateOrderStatus(extractSession(event), PaymentOrderStatus.FAILED);

            // Renewals only. The first invoice is already handled by
            // checkout.session.completed above, so acting on it here would
            // double-grant — hence the billing_reason == subscription_cycle guard.
            case "invoice.paid" -> handleRenewal(event);

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

    private void fulfill(Session session) {
        PaymentOrder order = resolveOrder(session);
        if (order == null) {
            log.warn("[Stripe] No order for session {} — cannot fulfil", session.getId());
            return;
        }
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            log.info("[Stripe] Order {} already PAID — skipping fulfilment", order.getId());
            return;
        }

        order.setStripePaymentIntentId(session.getPaymentIntent());

        switch (order.getPurpose()) {
            case CREDIT_PACK -> creditService.grant(
                    order.getUserId(), order.getCreditsGranted(),
                    CreditTransactionType.CREDIT_PURCHASE, null, order.getId(),
                    "Purchased " + (order.getCreditPack() != null ? order.getCreditPack().getDisplayName() : "credits"));

            case SUBSCRIPTION -> {
                order.setStripeSubscriptionId(session.getSubscription());
                upsertSubscription(order.getUserId(), session.getSubscription(), "active", false);
                creditService.grant(
                        order.getUserId(), order.getCreditsGranted(),
                        CreditTransactionType.SUBSCRIPTION_GRANT, null, order.getId(),
                        "Subscription — monthly credits");
            }
        }

        order.setStatus(PaymentOrderStatus.PAID);
        order.setFulfilledAt(LocalDateTime.now());
        paymentOrderRepository.save(order);
        log.info("[Stripe] Fulfilled order {} ({}) for user {}",
                order.getId(), order.getPurpose(), order.getUserId());
    }

    /** Monthly renewal: grant the subscription's credits again. */
    private void handleRenewal(Event event) {
        if (!(extractObject(event) instanceof Invoice invoice)) {
            return;
        }
        if (!"subscription_cycle".equals(invoice.getBillingReason())) {
            log.debug("[Stripe] invoice.paid billing_reason={} — not a renewal, skipping", invoice.getBillingReason());
            return;
        }
        String subscriptionId = subscriptionIdOf(invoice);
        if (subscriptionId == null) {
            log.warn("[Stripe] invoice.paid without a subscription id — skipping");
            return;
        }
        userSubscriptionRepository.findByStripeSubscriptionId(subscriptionId).ifPresentOrElse(sub -> {
            sub.setStatus("active");
            userSubscriptionRepository.save(sub);
            creditService.grant(sub.getUserId(), creditProperties.getSubscriptionMonthlyGrant(),
                    CreditTransactionType.SUBSCRIPTION_GRANT, null, null, "Subscription renewal — monthly credits");
            log.info("[Stripe] Renewed subscription {} for user {}", subscriptionId, sub.getUserId());
        }, () -> log.warn("[Stripe] Renewal for unknown subscription {}", subscriptionId));
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
