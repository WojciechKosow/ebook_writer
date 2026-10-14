package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.StoreOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, UUID> {

    Optional<StoreOrder> findByPaymentIntentId(String paymentIntentId);
}
