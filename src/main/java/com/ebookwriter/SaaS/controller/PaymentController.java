package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.entity.PaymentOrder;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.PaymentOrderRepository;
import com.ebookwriter.SaaS.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentOrderRepository paymentOrderRepository;
    private final CurrentUserService currentUserService;

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
        return new OrderStatusResponse(order.getId(), order.getStatus().name(),
                order.getPurpose().name(), order.getCreditsGranted());
    }
}
