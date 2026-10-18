package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import com.ebookwriter.SaaS.dto.store.StoreCheckoutResponse;
import com.ebookwriter.SaaS.dto.store.StoreOrderStatusResponse;
import com.ebookwriter.SaaS.entity.Store;
import com.ebookwriter.SaaS.entity.StoreOrder;
import com.ebookwriter.SaaS.entity.StoreOrderStatus;
import com.ebookwriter.SaaS.entity.StoreProduct;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.StoreOrderRepository;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.stripe.exception.StripeException;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Buyer checkout for creator stores. The client sends only a product id; the
 * seller, price, currency and order state all come from our database, and the
 * Checkout Session is a direct charge created ON the author's connected account.
 * No application fee is ever set — Scrivetta takes no commission.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreCheckoutService {

    /**
     * Shown next to the required terms-of-service checkbox in Checkout. The
     * buyer's acceptance is the consent to immediate supply of digital content and
     * the acknowledgement of losing the right of withdrawal (EU/UK buyers).
     */
    static final String CONSENT_TEXT = "I request immediate access to this digital content and acknowledge "
            + "that I lose my right of withdrawal once the download is made available to me.";

    private final StoreRepository storeRepository;
    private final StoreProductRepository productRepository;
    private final StoreOrderRepository orderRepository;
    private final ProductFileService fileService;
    private final StoreService storeService;
    private final StripeConnectGateway stripe;
    private final StripeConnectProperties connectProperties;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /**
     * Create a PENDING order and its Checkout Session. Re-checks server-side that
     * the store is published, its author has active PRO and Stripe has enabled
     * charges. On any failure the transaction rolls back, leaving no order behind.
     *
     * <p>{@code buyer} is the signed-in Scrivetta user, or null for a guest. A
     * verified buyer's order is linked to their account up front (and Checkout
     * uses their account email), so it shows in My Purchases once PAID.
     */
    @Transactional
    public StoreCheckoutResponse checkout(UUID productId, User buyer) {
        StoreProduct product = productRepository.findById(productId)
                .filter(StoreProduct::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        Store store = storeRepository.findById(product.getStoreId())
                .filter(Store::isPublished)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        String reason = storeService.unavailableReason(store);
        if (reason != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }
        if (!fileService.isDeliverable(product)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This product isn't available right now.");
        }
        User account = buyer != null && buyer.isEnabled() ? buyer : null;

        StoreOrder order = new StoreOrder();
        order.setStoreId(store.getId());
        order.setProductId(product.getId());
        order.setStripeAccountId(store.getStripeAccountId());
        order.setAmount(product.getPriceCents());
        order.setCurrency(product.getCurrency());
        order.setStatus(StoreOrderStatus.PENDING);
        if (account != null) {
            order.setBuyerUserId(account.getId());
        }
        order = orderRepository.saveAndFlush(order);

        String orderId = order.getId().toString();
        String base = frontendUrl + "/store/" + store.getSlug();
        SessionCreateParams.Builder builder = SessionCreateParams.builder();
        if (account != null) {
            // The receipt and the order go to the account's (verified) email.
            builder.setCustomerEmail(account.getEmail());
        }
        SessionCreateParams params = builder
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // Cards only: every payment settles synchronously, so
                // checkout.session.completed with payment_status=paid is final.
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(product.getCurrency())
                                .setUnitAmount(product.getPriceCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(product.getTitle())
                                        .build())
                                .build())
                        .build())
                .setClientReferenceId(orderId)
                .putMetadata("order_id", orderId)
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("order_id", orderId)
                        .build())
                .setConsentCollection(SessionCreateParams.ConsentCollection.builder()
                        .setTermsOfService(SessionCreateParams.ConsentCollection.TermsOfService.REQUIRED)
                        .build())
                .setCustomText(SessionCreateParams.CustomText.builder()
                        .setTermsOfServiceAcceptance(SessionCreateParams.CustomText.TermsOfServiceAcceptance.builder()
                                .setMessage(CONSENT_TEXT)
                                .build())
                        .build())
                .setExpiresAt(Instant.now().plusSeconds(60L * expiryMinutes()).getEpochSecond())
                .setSuccessUrl(base + "/success?order=" + orderId)
                .setCancelUrl(base + "/cancel?order=" + orderId)
                .build();

        StripeConnectGateway.CreatedSession session;
        try {
            session = stripe.createCheckoutSession(store.getStripeAccountId(), params);
        } catch (StripeException e) {
            log.error("[StripeConnect] Checkout failed for product {} on account {}: {}",
                    product.getId(), store.getStripeAccountId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not start checkout. Please try again.");
        }
        order.setCheckoutSessionId(session.id());
        orderRepository.save(order);
        log.info("[StripeConnect] Order {} PENDING — session {} on account {}",
                order.getId(), session.id(), store.getStripeAccountId());
        return new StoreCheckoutResponse(order.getId(), session.url());
    }

    /** What the success page polls. Reads our database only — the webhook decides PAID. */
    @Transactional(readOnly = true)
    public StoreOrderStatusResponse status(UUID orderId) {
        StoreOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        StoreProduct product = productRepository.findById(order.getProductId()).orElse(null);
        Store store = storeRepository.findById(order.getStoreId()).orElse(null);
        return new StoreOrderStatusResponse(order.getId(), order.getStatus(),
                product != null ? product.getTitle() : null,
                store != null ? store.getSlug() : null,
                store != null ? store.getName() : null);
    }

    private long expiryMinutes() {
        // Stripe accepts 30 minutes to 24 hours.
        return Math.max(31, Math.min(24 * 60, connectProperties.getCheckoutExpiryMinutes()));
    }
}
