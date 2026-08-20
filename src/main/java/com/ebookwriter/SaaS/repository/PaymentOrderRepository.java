package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByStripeCheckoutSessionId(String sessionId);

    Optional<PaymentOrder> findByStripePaymentIntentId(String paymentIntentId);
}
