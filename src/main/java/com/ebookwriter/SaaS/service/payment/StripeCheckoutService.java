package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.StripeProperties;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Creates Stripe-hosted Checkout sessions. Fulfilment (granting credits) is NOT
 * done here — it happens in {@link StripeWebhookService} when Stripe confirms
 * payment, so a user who closes the tab after paying still gets their credits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCheckoutService {

    private final StripeProperties stripeProperties;
    private final CreditProperties creditProperties;
    private final PaymentOrderRepository paymentOrderRepository;
    private final StripeCustomerService stripeCustomerService;

    public record CheckoutResult(UUID orderId, String checkoutUrl) {}

    /** Buy a one-time credit pack. */
    @Transactional
    public CheckoutResult createCreditPackCheckout(User user, CreditPack pack) {
        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .userId(user.getId())
                .purpose(PaymentPurpose.CREDIT_PACK)
                .creditPack(pack)
                .creditsGranted(pack.getCredits())
                .amountCents(pack.getPriceCents())
                .currency(stripeProperties.getCurrency())
                .status(PaymentOrderStatus.CREATED)
                .build());

        SessionCreateParams params = baseParams(order, SessionCreateParams.Mode.PAYMENT)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(stripeProperties.getCurrency())
                                .setUnitAmount((long) pack.getPriceCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("eBook Writer — " + pack.getDisplayName())
                                        .build())
                                .build())
                        .build())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("orderId", order.getId().toString())
                        .build())
                .build();

        return startSession(order, params);
    }

    /** Start the recurring subscription checkout. */
    @Transactional
    public CheckoutResult createSubscriptionCheckout(User user) {
        String priceId = stripeProperties.getSubscriptionPriceId();
        if (priceId == null || priceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Subscriptions are not configured (set stripe.subscription-price-id)");
        }

        String customerId;
        try {
            customerId = stripeCustomerService.getOrCreateCustomerId(user);
        } catch (StripeException e) {
            log.error("[Stripe] Could not resolve customer for user {}: {}", user.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start checkout");
        }

        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .userId(user.getId())
                .purpose(PaymentPurpose.SUBSCRIPTION)
                .creditsGranted(creditProperties.getSubscriptionMonthlyGrant())
                .amountCents(0)
                .currency(stripeProperties.getCurrency())
                .status(PaymentOrderStatus.CREATED)
                .build());

        SessionCreateParams params = baseParams(order, SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPrice(priceId)
                        .build())
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("userId", user.getId().toString())
                        .putMetadata("orderId", order.getId().toString())
                        .build())
                .build();

        return startSession(order, params);
    }

    // ------------------------------------------------------------------------

    private SessionCreateParams.Builder baseParams(PaymentOrder order, SessionCreateParams.Mode mode) {
        String successUrl = stripeProperties.getSuccessUrl().replace("{ORDER_ID}", order.getId().toString());
        return SessionCreateParams.builder()
                .setMode(mode)
                .setSuccessUrl(successUrl)
                .setCancelUrl(stripeProperties.getCancelUrl())
                .setClientReferenceId(order.getId().toString())
                .putMetadata("orderId", order.getId().toString())
                .putMetadata("userId", order.getUserId().toString())
                .putMetadata("purpose", order.getPurpose().name());
    }

    private CheckoutResult startSession(PaymentOrder order, SessionCreateParams params) {
        // Idempotency: retrying session creation for the same order never
        // creates a second Stripe session (guards double-clicks / retries).
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("checkout-session-" + order.getId())
                .build();
        try {
            Session session = Session.create(params, options);
            order.setStripeCheckoutSessionId(session.getId());
            order.setStatus(PaymentOrderStatus.PENDING);
            paymentOrderRepository.save(order);
            log.info("[Stripe] Created checkout session {} for order {} ({})",
                    session.getId(), order.getId(), order.getPurpose());
            return new CheckoutResult(order.getId(), session.getUrl());
        } catch (StripeException e) {
            order.setStatus(PaymentOrderStatus.FAILED);
            paymentOrderRepository.save(order);
            log.error("[Stripe] Failed to create checkout session for order {}: {}", order.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start checkout");
        }
    }
}
