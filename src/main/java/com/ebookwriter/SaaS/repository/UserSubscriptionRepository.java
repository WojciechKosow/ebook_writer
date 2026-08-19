package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, UUID> {

    Optional<UserSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}
