package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.CheckoutResponse;
import com.ebookwriter.SaaS.dto.SubscriptionResponse;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.payment.StripeCheckoutService;
import com.ebookwriter.SaaS.service.payment.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final StripeCheckoutService checkoutService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public SubscriptionResponse state(Authentication authentication) {
        User user = currentUserService.require(authentication);
        return subscriptionService.getState(user.getId());
    }

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(Authentication authentication) {
        User user = currentUserService.require(authentication);
        StripeCheckoutService.CheckoutResult result = checkoutService.createSubscriptionCheckout(user);
        return ResponseEntity.ok(new CheckoutResponse(result.orderId(), result.checkoutUrl()));
    }

    @PostMapping("/cancel")
    public SubscriptionResponse cancel(Authentication authentication) {
        return subscriptionService.cancelAtPeriodEnd(currentUserService.require(authentication));
    }

    @PostMapping("/resume")
    public SubscriptionResponse resume(Authentication authentication) {
        return subscriptionService.resume(currentUserService.require(authentication));
    }
}
