package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.UserSubscription;

import java.time.LocalDateTime;

public record SubscriptionResponse(
        boolean active,
        String status,
        boolean cancelAtPeriodEnd,
        LocalDateTime currentPeriodEnd
) {
    public static SubscriptionResponse none() {
        return new SubscriptionResponse(false, "none", false, null);
    }

    public static SubscriptionResponse from(UserSubscription sub) {
        boolean active = "active".equals(sub.getStatus()) || "trialing".equals(sub.getStatus());
        return new SubscriptionResponse(active, sub.getStatus(), sub.isCancelAtPeriodEnd(), sub.getCurrentPeriodEnd());
    }
}
