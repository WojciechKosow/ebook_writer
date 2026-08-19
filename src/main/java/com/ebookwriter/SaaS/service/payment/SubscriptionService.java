package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.dto.SubscriptionResponse;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserSubscription;
import com.ebookwriter.SaaS.repository.UserSubscriptionRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final UserSubscriptionRepository userSubscriptionRepository;

    @Transactional(readOnly = true)
    public SubscriptionResponse getState(UUID userId) {
        return userSubscriptionRepository.findById(userId)
                .map(SubscriptionResponse::from)
                .orElseGet(SubscriptionResponse::none);
    }

    /** Cancel at period end (keeps access + credits until the period runs out). */
    @Transactional
    public SubscriptionResponse cancelAtPeriodEnd(User user) {
        return setCancel(user, true);
    }

    /** Undo a pending cancellation. */
    @Transactional
    public SubscriptionResponse resume(User user) {
        return setCancel(user, false);
    }

    private SubscriptionResponse setCancel(User user, boolean cancel) {
        UserSubscription sub = userSubscriptionRepository.findById(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription"));
        if (sub.getStripeSubscriptionId() == null || sub.getStripeSubscriptionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No active subscription");
        }
        try {
            Subscription stripeSub = Subscription.retrieve(sub.getStripeSubscriptionId());
            stripeSub.update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(cancel).build());
        } catch (StripeException e) {
            log.error("[Stripe] Could not update subscription {}: {}", sub.getStripeSubscriptionId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not update subscription");
        }
        sub.setCancelAtPeriodEnd(cancel);
        userSubscriptionRepository.save(sub);
        return SubscriptionResponse.from(sub);
    }
}
