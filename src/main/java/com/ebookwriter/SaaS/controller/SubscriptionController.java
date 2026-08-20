package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.CheckoutResponse;
import com.ebookwriter.SaaS.dto.SubscriptionResponse;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.payment.StripeCheckoutService;
import com.ebookwriter.SaaS.service.payment.StripeCustomerService;
import com.ebookwriter.SaaS.service.payment.SubscriptionService;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final StripeCheckoutService checkoutService;
    private final StripeCustomerService customerService;
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

    /** Stripe billing portal — the user manages/cancels their subscription there. */
    @PostMapping("/portal")
    public ResponseEntity<Map<String, String>> portal(Authentication authentication) {
        User user = currentUserService.require(authentication);
        try {
            String url = customerService.createBillingPortalUrl(user);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not open the billing portal");
        }
    }
}
