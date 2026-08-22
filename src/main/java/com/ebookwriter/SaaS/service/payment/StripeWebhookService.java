package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.StripeProperties;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.ebookwriter.SaaS.repository.ProcessedStripeEventRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.repository.UserSubscriptionRepository;
import com.ebookwriter.SaaS.service.credit.CreditService;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
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
    private final UserRepository userRepository;
    private final CreditService creditService;

    @Transactional
    public void handle(String payload, String signatureHeader) {
        String webhookSecret = stripeProperties.getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            // Misconfiguration — not a bad request. Without the signing secret we
            // cannot verify or fulfil anything, and every payment would silently
            // hang on "processing". Return 503 so Stripe keeps retrying and the
            // failure is obvious in both our logs and the Stripe dashboard.
            log.error("[Stripe] Cannot process webhook: stripe.webhook-secret is not configured "
                    + "— set STRIPE_WEBHOOK_SECRET. Paid orders cannot be fulfilled until it is set.");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook not configured");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
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

            // Dunning: a renewal charge failed. Stripe will retry per your
            // dunning settings; mark the subscription past_due so no fresh
            // credits are granted until a payment succeeds again.
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(event);

            // Clawbacks: reclaim credits when money is pulled back so a refund or
            // chargeback can't leave the user with free credits.
            case "charge.refunded" -> handleChargeRefunded(event);
            case "charge.dispute.created" -> handleChargeDisputed(event);

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
                // Persist the order's PAID state BEFORE granting. grant() runs a
                // bulk update that clears the persistence context: if the order is
                // still dirty/managed at that point, the follow-up save collides on
                // its @Version (ObjectOptimisticLockingFailureException) and the
                // whole webhook rolls back — credits lost and the order stuck
                // PENDING ("payment is processing" forever). Flushing first, then
                // never touching the order again, keeps fulfilment atomic and safe.
                markPaid(order);
                creditService.grant(order.getUserId(), order.getCreditsGranted(),
                        CreditTransactionType.CREDIT_PURCHASE, null, order.getId(),
                        "Purchased " + (order.getCreditPack() != null ? order.getCreditPack().getDisplayName() : "credits"));
                log.info("[Stripe] Fulfilled order {} (CREDIT_PACK) for user {}", order.getId(), order.getUserId());
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
                markPaid(order);
                log.info("[Stripe] Fulfilled order {} (SUBSCRIPTION) for user {}", order.getId(), order.getUserId());
            }
        }
    }

    /** Flip an order to PAID and flush it immediately, so later context-clearing
     *  writes (e.g. the bulk update inside a credit grant) can't strand it. */
    private void markPaid(PaymentOrder order) {
        order.setStatus(PaymentOrderStatus.PAID);
        order.setFulfilledAt(LocalDateTime.now());
        paymentOrderRepository.saveAndFlush(order);
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

    /**
     * A subscription renewal (or first) payment failed. Reflect it as past_due;
     * Stripe retries on its dunning schedule, and a later invoice.paid grants the
     * credits while a final failure arrives as customer.subscription.deleted.
     */
    private void handleInvoicePaymentFailed(Event event) {
        if (!(extractObject(event) instanceof Invoice invoice)) {
            return;
        }
        String subscriptionId = subscriptionIdOf(invoice);
        if (subscriptionId == null) {
            return;
        }
        userSubscriptionRepository.findByStripeSubscriptionId(subscriptionId).ifPresent(sub -> {
            sub.setStatus("past_due");
            userSubscriptionRepository.save(sub);
            log.info("[Stripe] invoice.payment_failed → subscription {} marked past_due", subscriptionId);
        });
    }

    /**
     * A charge was (partially or fully) refunded — reclaim credits in proportion
     * to the amount refunded.
     */
    private void handleChargeRefunded(Event event) {
        if (!(extractObject(event) instanceof Charge charge)) {
            return;
        }
        String paymentIntentId = charge.getPaymentIntent();
        long amount = charge.getAmount() != null ? charge.getAmount() : 0L;
        long refunded = charge.getAmountRefunded() != null ? charge.getAmountRefunded() : 0L;
        if (paymentIntentId == null || amount <= 0 || refunded <= 0) {
            log.warn("[Stripe] charge.refunded with no usable amount/payment_intent — skipping");
            return;
        }
        double fraction = Math.min(1.0, (double) refunded / amount);
        reclaimForCharge(paymentIntentId, charge.getCustomer(), fraction, refunded >= amount, "Refund");
    }

    /**
     * A chargeback (dispute) was opened — a chargeback pulls the whole payment,
     * so reclaim everything that charge granted.
     */
    private void handleChargeDisputed(Event event) {
        if (!(extractObject(event) instanceof Dispute dispute)) {
            return;
        }
        String paymentIntentId = dispute.getPaymentIntent();
        String customerId = null;
        if (dispute.getCharge() != null) {
            try {
                Charge charge = Charge.retrieve(dispute.getCharge());
                if (paymentIntentId == null) {
                    paymentIntentId = charge.getPaymentIntent();
                }
                customerId = charge.getCustomer();
            } catch (StripeException e) {
                log.warn("[Stripe] Could not retrieve charge {} for dispute: {}", dispute.getCharge(), e.getMessage());
            }
        }
        if (paymentIntentId == null) {
            log.warn("[Stripe] dispute with no resolvable payment_intent — skipping");
            return;
        }
        reclaimForCharge(paymentIntentId, customerId, 1.0, true, "Chargeback");
    }

    /**
     * Reclaim credits for a charge that was refunded or charged back. The charge
     * is matched to what it originally granted:
     *   - a credit-pack order (by payment_intent) → its {@code creditsGranted};
     *   - otherwise a subscription payment → the monthly grant, with the user
     *     resolved from the order or the Stripe customer.
     * Reclaims {@code round(granted * fraction)} minus whatever was already
     * reclaimed for this payment_intent, so partial refunds and a
     * refund-then-dispute sequence stay idempotent.
     */
    private void reclaimForCharge(String paymentIntentId, String customerId,
                                  double fraction, boolean full, String label) {
        PaymentOrder order = paymentOrderRepository.findByStripePaymentIntentId(paymentIntentId).orElse(null);

        UUID userId;
        int granted;
        if (order != null && order.getPurpose() == PaymentPurpose.CREDIT_PACK) {
            userId = order.getUserId();
            granted = order.getCreditsGranted();
        } else {
            userId = order != null ? order.getUserId() : resolveUserByCustomer(customerId);
            granted = creditProperties.getSubscriptionMonthlyGrant();
        }
        if (userId == null) {
            log.warn("[Stripe] {} for payment_intent {} — could not resolve a user, skipping",
                    label, paymentIntentId);
            return;
        }

        int target = (int) Math.round(granted * fraction);
        int already = creditService.clawedForReference(paymentIntentId);
        int toReclaim = target - already;
        if (toReclaim <= 0) {
            log.info("[Stripe] {} for payment_intent {} — nothing further to reclaim (target {}, already {})",
                    label, paymentIntentId, target, already);
            return;
        }

        creditService.clawback(userId, toReclaim, paymentIntentId, label + " — credits reclaimed");

        if (full && order != null && order.getStatus() != PaymentOrderStatus.REFUNDED) {
            order.setStatus(PaymentOrderStatus.REFUNDED);
            paymentOrderRepository.save(order);
        }
        log.info("[Stripe] {} reclaimed {} credits from user {} for payment_intent {}",
                label, toReclaim, userId, paymentIntentId);
    }

    private UUID resolveUserByCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        return userRepository.findByStripeCustomerId(customerId).map(User::getId).orElse(null);
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
