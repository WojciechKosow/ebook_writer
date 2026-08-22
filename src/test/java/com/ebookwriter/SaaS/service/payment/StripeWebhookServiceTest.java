package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.entity.CreditPack;
import com.ebookwriter.SaaS.entity.PaymentOrder;
import com.ebookwriter.SaaS.entity.PaymentOrderStatus;
import com.ebookwriter.SaaS.entity.PaymentPurpose;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.ebookwriter.SaaS.service.credit.CreditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end fulfilment of a one-time credit-pack purchase through the signed
 * Stripe webhook — the path a real payment takes. Signatures are computed the
 * same way Stripe does, so {@link com.stripe.net.Webhook#constructEvent} accepts
 * them without any network calls. Proves that a paid checkout grants credits and
 * flips the order to PAID, that duplicate deliveries never double-grant, and that
 * a forged signature is rejected.
 */
@SpringBootTest
@TestPropertySource(properties = "stripe.webhook-secret=whsec_test_secret_for_unit_tests")
class StripeWebhookServiceTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests";

    @Autowired
    StripeWebhookService webhookService;
    @Autowired
    PaymentOrderRepository paymentOrderRepository;
    @Autowired
    CreditService creditService;

    @Test
    void paidCreditPackCheckoutGrantsCreditsAndMarksOrderPaid() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.PACK_150);

        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid");
        webhookService.handle(payload, sign(payload));

        assertEquals(150, creditService.getBalance(userId), "credits must be granted on a paid checkout");
        assertEquals(PaymentOrderStatus.PAID,
                paymentOrderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void paidCheckoutForUserWithExistingBalanceCommitsAndClimbs() {
        UUID userId = UUID.randomUUID();
        // Mirror production: the user already has a wallet with a prior balance.
        creditService.grant(userId, 150, com.ebookwriter.SaaS.entity.CreditTransactionType.CREDIT_PURCHASE,
                null, UUID.randomUUID(), "earlier purchase");
        assertEquals(150, creditService.getBalance(userId));

        PaymentOrder order = newPendingPackOrder(userId, CreditPack.PACK_150);
        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid");
        webhookService.handle(payload, sign(payload));

        // The whole webhook transaction must COMMIT: balance climbs to 300 and the
        // order flips to PAID. If the grant rolls back, balance stays 150 and the
        // order stays PENDING — the "processing forever" bug.
        assertEquals(300, creditService.getBalance(userId), "balance must climb and commit");
        assertEquals(PaymentOrderStatus.PAID,
                paymentOrderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void duplicateEventDeliveryDoesNotDoubleGrant() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.PACK_60);

        String eventId = "evt_" + UUID.randomUUID();
        String payload = checkoutCompletedEvent(eventId, order.getId(), "paid");

        webhookService.handle(payload, sign(payload));
        // Stripe re-delivers the same event id — must be a no-op.
        webhookService.handle(payload, sign(payload));

        assertEquals(60, creditService.getBalance(userId), "a redelivered event must not grant twice");
    }

    @Test
    void unpaidCheckoutLeavesOrderPendingAndGrantsNothing() {
        UUID userId = UUID.randomUUID();
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.PACK_25);

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
        PaymentOrder order = newPendingPackOrder(userId, CreditPack.PACK_25);
        String payload = checkoutCompletedEvent("evt_" + UUID.randomUUID(), order.getId(), "paid");

        assertThrows(ResponseStatusException.class,
                () -> webhookService.handle(payload, "t=1,v1=deadbeef"));
        assertEquals(0, creditService.getBalance(userId), "a forged webhook must never grant credits");
    }

    // ------------------------------------------------------------------------

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
