package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.entity.UserSubscription;
import com.ebookwriter.SaaS.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO (and with it the Marketplace storefront) follows the server-side
 * subscription record mirrored from Stripe — never a client flag.
 */
@SpringBootTest
class SubscriptionEntitlementTest {

    @Autowired SubscriptionService subscriptionService;
    @Autowired UserSubscriptionRepository repository;

    @Test
    void activeAndTrialingSubscriptionsArePro() {
        assertTrue(subscriptionService.isPro(withStatus("active")));
        assertTrue(subscriptionService.isPro(withStatus("trialing")));
    }

    @Test
    void aSubscriptionSetToCancelStaysProUntilItEnds() {
        UUID user = withStatus("active");
        UserSubscription sub = repository.findById(user).orElseThrow();
        sub.setCancelAtPeriodEnd(true);
        repository.save(sub);

        assertTrue(subscriptionService.isPro(user));
    }

    @Test
    void failedRenewalsCancelledAndUnknownUsersAreNotPro() {
        assertFalse(subscriptionService.isPro(withStatus("past_due")), "no grace period beyond Stripe's own");
        assertFalse(subscriptionService.isPro(withStatus("canceled")));
        assertFalse(subscriptionService.isPro(withStatus("incomplete")));
        assertFalse(subscriptionService.isPro(UUID.randomUUID()), "BASIC: no subscription at all");
    }

    private UUID withStatus(String status) {
        UUID user = UUID.randomUUID();
        repository.save(UserSubscription.builder().userId(user).stripeSubscriptionId("sub_" + user)
                .status(status).build());
        return user;
    }
}
