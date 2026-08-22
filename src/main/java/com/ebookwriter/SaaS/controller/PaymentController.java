package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.entity.PaymentOrder;
import com.ebookwriter.SaaS.entity.PaymentOrderStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.payment.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentOrderRepository paymentOrderRepository;
    private final CurrentUserService currentUserService;
    private final StripeWebhookService webhookService;

    public record OrderStatusResponse(UUID orderId, String status, String purpose, int creditsGranted) {}

    /** Poll the real outcome of a checkout — the browser redirect is never trusted. */
    @GetMapping("/orders/{id}")
    public OrderStatusResponse order(@PathVariable UUID id, Authentication authentication) {
        User user = currentUserService.require(authentication);
        PaymentOrder order = paymentOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(user.getId())) {
            // Don't reveal another user's order.
            throw new IllegalArgumentException("Order not found");
        }

        // Self-heal: if the order hasn't been fulfilled yet, ask Stripe directly
        // whether the checkout was paid instead of waiting on the webhook. This
        // makes a returning user get credited even when the webhook is delayed or
        // the endpoint isn't subscribed to checkout.session.completed. Idempotent
        // and safe against a racing webhook.
        if (order.getStatus() == PaymentOrderStatus.CREATED || order.getStatus() == PaymentOrderStatus.PENDING) {
            try {
                webhookService.reconcilePendingOrder(order.getId());
            } catch (Exception e) {
                log.warn("[Stripe] Reconcile on poll failed for order {}: {}", order.getId(), e.getMessage());
            }
            order = paymentOrderRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        }

        return new OrderStatusResponse(order.getId(), order.getStatus().name(),
                order.getPurpose().name(), order.getCreditsGranted());
    }
}
