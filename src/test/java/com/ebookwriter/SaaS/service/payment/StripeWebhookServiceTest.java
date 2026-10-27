package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.entity.CreditPack;
import com.ebookwriter.SaaS.entity.PaymentOrder;
import com.ebookwriter.SaaS.entity.PaymentOrderStatus;
import com.ebookwriter.SaaS.entity.PaymentPurpose;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.UserSubscription;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.ebookwriter.SaaS.repository.UserSubscriptionRepository;
import com.ebookwriter.SaaS.service.credit.CreditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * End-to-end fulfilment of a one-time credit-pack purchase through the signed
 * Stripe webhook — the path a real payment takes. Signatures are computed the
 * same way Stripe does, so {@link com.stripe.net.Webhook#constructEvent} accepts
 * them without any network calls. Proves that a paid checkout grants credits and
 * flips the order to PAID, that duplicate deliveries never double-grant, and that
 * a forged signature is rejected — plus the subscription lifecycle: monthly
 * credits per paid invoice (once per invoice, whatever the delivery count),
 * renewal expiry, legacy-price allowances, PRO ending, and the refund/chargeback
 * clawback policy.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "stripe.webhook-secret=whsec_test_secret_for_unit_tests",
        "stripe.subscription-price-id=price_pro_2999",
        "stripe.legacy-subscription-price-ids=price_pro_1999"
})
class StripeWebhookServiceTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests";

    @Autowired
    StripeWebhookService webhookService;
    @Autowired
    PaymentOrderRepository paymentOrderRepository;
    @Autowired
    CreditService creditService;
    @Autowired
    UserSubscriptionRepository userSubscriptionRepository;
    @MockitoBean
    StripeInvoiceLookup invoiceLookup;

    @Test
    void paidCreditPackCheckoutGrantsCreditsAndMarksOrderPaid() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.CREATOR);

        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid");
        webhookService.handle(payload, sign(payload));

        assertEquals(1200, creditService.getBalance(userId), "credits must be granted on a paid checkout");
        assertEquals(1200, creditService.getWallet(userId).purchasedCredits(), "pack credits never expire");
        assertEquals(PaymentOrderStatus.PAID,
                paymentOrderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void paidCheckoutForUserWithExistingBalanceCommitsAndClimbs() {
        UUID userId = UUID.randomUUID();
        // Mirror production: the user already has a wallet with a prior balance.
        creditService.grant(userId, 500, CreditTransactionType.CREDIT_PURCHASE,
                null, UUID.randomUUID(), "earlier purchase");
        assertEquals(500, creditService.getBalance(userId));

        PaymentOrder order = newPendingPackOrder(userId, CreditPack.STUDIO);
        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid");
        webhookService.handle(payload, sign(payload));

        // The whole webhook transaction must COMMIT: balance climbs to 3000 and the
        // order flips to PAID. If the grant rolls back, balance stays 500 and the
        // order stays PENDING — the "processing forever" bug.
        assertEquals(3000, creditService.getBalance(userId), "balance must climb and commit");
        assertEquals(PaymentOrderStatus.PAID,
                paymentOrderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void duplicateEventDeliveryDoesNotDoubleGrant() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.STARTER);

        String eventId = "evt_" + UUID.randomUUID();
        String payload = checkoutCompletedEvent(eventId, order.getId(), "paid");

        webhookService.handle(payload, sign(payload));
        // Stripe re-delivers the same event id — must be a no-op.
        webhookService.handle(payload, sign(payload));

        assertEquals(500, creditService.getBalance(userId), "a redelivered event must not grant twice");
    }

    @Test
    void unpaidCheckoutLeavesOrderPendingAndGrantsNothing() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.STARTER);

        // Async/delayed payment methods complete the session before the money
        // settles: payment_status="unpaid". Credits must wait for the later
        // async_payment_succeeded event.
        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "unpaid");
        webhookService.handle(payload, sign(payload));

        assertEquals(0, creditService.getBalance(userId));
        assertEquals(PaymentOrderStatus.PENDING,
                paymentOrderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void forgedSignatureIsRejected() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.STARTER);
        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid");

        assertThrows(ResponseStatusException.class,
                () -> webhookService.handle(payload, "t=1,v1=deadbeef"));
        assertEquals(0, creditService.getBalance(userId), "a forged webhook must never grant credits");
    }

    @Test
    void theNewPacksArePricedAsPublishedAndRetiredPacksAreNotForSale() {
        assertEquals(500, CreditPack.STARTER.getCredits());
        assertEquals(799, CreditPack.STARTER.getPriceCents());
        assertEquals(1200, CreditPack.CREATOR.getCredits());
        assertEquals(1999, CreditPack.CREATOR.getPriceCents());
        assertEquals(2500, CreditPack.STUDIO.getCredits());
        assertEquals(3499, CreditPack.STUDIO.getPriceCents());
        assertEquals(java.util.List.of(CreditPack.STARTER, CreditPack.CREATOR, CreditPack.STUDIO),
                java.util.Arrays.stream(CreditPack.values()).filter(CreditPack::isAvailable).toList());
    }

    // ---- subscription lifecycle --------------------------------------------

    @Test
    void aPaidSubscriptionInvoiceGrantsTheMonthlyCreditsOncePerInvoice() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);
        String invoice = "in_" + UUID.randomUUID();

        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), invoice, sub, "subscription_create", "price_pro_2999"));
        assertEquals(3000, creditService.getBalance(userId));
        assertEquals(3000, creditService.getWallet(userId).subscriptionCredits());

        // The same event redelivered, and a *different* event for the same invoice: no second allowance.
        String again = invoicePaidEvent("evt_" + UUID.randomUUID(), invoice, sub, "subscription_create", "price_pro_2999");
        deliver(again);
        deliver(again);
        assertEquals(3000, creditService.getBalance(userId), "one allowance per paid invoice");
        UserSubscription mirrored = userSubscriptionRepository.findById(userId).orElseThrow();
        assertNotNull(mirrored.getCurrentPeriodEnd());
        assertEquals("price_pro_2999", mirrored.getStripePriceId());
    }

    @Test
    void aRenewalReplacesTheUnusedMonthlyCreditsAndKeepsPackCredits() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);
        creditService.grant(userId, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), "in_1_" + userId, sub, "subscription_create", "price_pro_2999"));
        creditService.spend(userId, 1000, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");

        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), "in_2_" + userId, sub, "subscription_cycle", "price_pro_2999"));

        CreditService.Wallet wallet = creditService.getWallet(userId);
        assertEquals(3000, wallet.subscriptionCredits(), "monthly credits don't accumulate");
        assertEquals(500, wallet.purchasedCredits(), "pack credits are kept");
    }

    @Test
    void aLegacyPricedSubscriberGetsTheLegacyAllowance() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);

        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), "in_" + UUID.randomUUID(), sub, "subscription_cycle",
                "price_pro_1999"));

        assertEquals(1650, creditService.getBalance(userId), "150 pages at 11 credits — what the old plan paid for");
    }

    @Test
    void theSubscriptionCheckoutItselfGrantsNoCredits() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .userId(userId).purpose(PaymentPurpose.SUBSCRIPTION).creditsGranted(3000).amountCents(0)
                .currency("usd").status(PaymentOrderStatus.PENDING).build());

        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid")
                .replace("\"mode\": \"payment\"", "\"mode\": \"subscription\", \"subscription\": \"sub_" + userId + "\"");
        deliver(payload);

        assertEquals(0, creditService.getBalance(userId), "credits come only from the paid invoice");
        assertEquals("active", userSubscriptionRepository.findById(userId).orElseThrow().getStatus());
    }

    @Test
    void endingProExpiresMonthlyCreditsButKeepsPackCredits() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);
        creditService.grant(userId, 1200, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), "in_" + UUID.randomUUID(), sub, "subscription_create",
                "price_pro_2999"));

        deliver(subscriptionDeletedEvent("evt_" + UUID.randomUUID(), sub));

        assertEquals(1200, creditService.getBalance(userId));
        assertEquals("canceled", userSubscriptionRepository.findById(userId).orElseThrow().getStatus());
    }

    // ---- refunds and chargebacks --------------------------------------------

    @Test
    void aRefundedPackReclaimsOnlyItsUnspentCreditsAndNeverGoesNegative() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.STARTER);
        deliver(checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid"));
        creditService.spend(userId, 300, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");

        deliver(chargeRefundedEvent("evt_" + UUID.randomUUID(), "pi_test_" + order.getId(), 799, 799, null));
        // Another refund event for the same charge must not take anything more.
        deliver(chargeRefundedEvent("evt_" + UUID.randomUUID(), "pi_test_" + order.getId(), 799, 799, null));

        assertEquals(0, creditService.getBalance(userId), "200 unspent reclaimed, never below zero");
        assertEquals(500, creditService.clawedForReference("pi_test_" + order.getId()));
        assertEquals(PaymentOrderStatus.REFUNDED, paymentOrderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void aRefundedPackNeverTouchesMonthlyCredits() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);
        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), "in_" + UUID.randomUUID(), sub, "subscription_create",
                "price_pro_2999"));
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.STARTER);
        deliver(checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid"));

        deliver(chargeRefundedEvent("evt_" + UUID.randomUUID(), "pi_test_" + order.getId(), 799, 799, null));

        assertEquals(3000, creditService.getBalance(userId));
        assertEquals(3000, creditService.getWallet(userId).subscriptionCredits());
    }

    @Test
    void aRefundedCurrentSubscriptionPaymentReclaimsUnspentMonthlyCredits() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);
        String invoice = "in_" + UUID.randomUUID();
        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), invoice, sub, "subscription_create", "price_pro_2999"));
        creditService.grant(userId, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.spend(userId, 1000, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");
        String pi = "pi_sub_" + UUID.randomUUID();
        PaymentOrder subscriptionOrder = paymentOrderRepository.save(PaymentOrder.builder()
                .userId(userId).purpose(PaymentPurpose.SUBSCRIPTION).creditsGranted(3000).amountCents(0)
                .currency("usd").status(PaymentOrderStatus.PAID).stripePaymentIntentId(pi).build());
        when(invoiceLookup.invoiceForPaymentIntent(pi)).thenReturn(Optional.of(invoice));

        deliver(chargeRefundedEvent("evt_" + UUID.randomUUID(), pi, 2999, 2999, null));

        CreditService.Wallet wallet = creditService.getWallet(userId);
        assertEquals(0, wallet.subscriptionCredits(), "the 2000 unspent monthly credits are reclaimed");
        assertEquals(500, wallet.purchasedCredits(), "pack credits untouched");
        assertNotNull(subscriptionOrder.getId());
    }

    @Test
    void aRefundedPastPeriodTakesNothingFromTheCurrentPeriod() {
        UUID userId = UUID.randomUUID();
        String sub = subscribed(userId);
        String oldInvoice = "in_old_" + UUID.randomUUID();
        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), oldInvoice, sub, "subscription_create", "price_pro_2999"));
        deliver(invoicePaidEvent("evt_" + UUID.randomUUID(), "in_new_" + UUID.randomUUID(), sub, "subscription_cycle",
                "price_pro_2999"));
        String pi = "pi_sub_" + UUID.randomUUID();
        paymentOrderRepository.save(PaymentOrder.builder()
                .userId(userId).purpose(PaymentPurpose.SUBSCRIPTION).creditsGranted(3000).amountCents(0)
                .currency("usd").status(PaymentOrderStatus.PAID).stripePaymentIntentId(pi).build());
        when(invoiceLookup.invoiceForPaymentIntent(pi)).thenReturn(Optional.of(oldInvoice));

        deliver(chargeRefundedEvent("evt_" + UUID.randomUUID(), pi, 2999, 2999, null));

        assertEquals(3000, creditService.getBalance(userId), "the current period's credits are unrelated");
    }

    // ------------------------------------------------------------------------

    private void deliver(String payload) {
        webhookService.handle(payload, sign(payload));
    }

    /** A user with a mirrored Stripe subscription, as checkout leaves it. */
    private String subscribed(UUID userId) {
        String sub = "sub_" + userId;
        userSubscriptionRepository.save(UserSubscription.builder()
                .userId(userId).stripeSubscriptionId(sub).status("active").build());
        return sub;
    }

    private String invoicePaidEvent(String eventId, String invoiceId, String subscriptionId, String reason,
                                    String priceId) {
        return """
               {
                 "id": "%s",
                 "object": "event",
                 "api_version": "2025-09-30.clover",
                 "type": "invoice.paid",
                 "data": {
                   "object": {
                     "id": "%s",
                     "object": "invoice",
                     "billing_reason": "%s",
                     "parent": {
                       "type": "subscription_details",
                       "subscription_details": { "subscription": "%s" }
                     },
                     "lines": {
                       "object": "list",
                       "data": [{
                         "id": "il_1",
                         "object": "line_item",
                         "period": { "start": 1790000000, "end": 1792592000 },
                         "pricing": {
                           "type": "price_details",
                           "price_details": { "price": "%s", "product": "prod_pro" }
                         }
                       }]
                     }
                   }
                 }
               }""".formatted(eventId, invoiceId, reason, subscriptionId, priceId);
    }

    private String subscriptionDeletedEvent(String eventId, String subscriptionId) {
        return """
               {
                 "id": "%s",
                 "object": "event",
                 "api_version": "2025-09-30.clover",
                 "type": "customer.subscription.deleted",
                 "data": { "object": { "id": "%s", "object": "subscription", "status": "canceled" } }
               }""".formatted(eventId, subscriptionId);
    }

    private String chargeRefundedEvent(String eventId, String paymentIntent, long amount, long refunded,
                                       String customer) {
        return """
               {
                 "id": "%s",
                 "object": "event",
                 "api_version": "2025-09-30.clover",
                 "type": "charge.refunded",
                 "data": {
                   "object": {
                     "id": "ch_%s",
                     "object": "charge",
                     "payment_intent": "%s",
                     "amount": %d,
                     "amount_refunded": %d,
                     "customer": %s
                   }
                 }
               }""".formatted(eventId, UUID.randomUUID(), paymentIntent, amount, refunded,
                customer == null ? "null" : "\"" + customer + "\"");
    }

    private PaymentOrder newPendingPackOrder(UUID userId, CreditPack pack) {
        return paymentOrderRepository.save(PaymentOrder.builder()
                .userId(userId)
                .purpose(PaymentPurpose.CREDIT_PACK)
                .creditPack(pack)
                .creditsGranted(pack.getCredits())
                .amountCents(pack.getPriceCents())
                .currency("usd")
                .status(PaymentOrderStatus.PENDING)
                .build());
    }

    /** Minimal but valid checkout.session.completed event JSON. */
    private String checkoutCompletedEvent(String eventId, UUID orderId, String paymentStatus) {
        return """
               {
                 "id": "%s",
                 "object": "event",
                 "api_version": "2024-06-20",
                 "type": "checkout.session.completed",
                 "data": {
                   "object": {
                     "id": "cs_test_%s",
                     "object": "checkout.session",
                     "mode": "payment",
                     "payment_status": "%s",
                     "payment_intent": "pi_test_%s",
                     "client_reference_id": "%s"
                   }
                 }
               }""".formatted(eventId, orderId, paymentStatus, orderId, orderId);
    }

    /** Build a Stripe-Signature header exactly as Stripe signs webhook payloads. */
    private String sign(String payload) {
        long timestamp = System.currentTimeMillis() / 1000L;
        String signedPayload = timestamp + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
