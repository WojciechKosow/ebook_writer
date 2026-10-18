package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.StoreOrder;
import com.ebookwriter.SaaS.entity.StoreOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, UUID> {

    Optional<StoreOrder> findByPaymentIntentId(String paymentIntentId);

    List<StoreOrder> findByBuyerUserIdAndStatusOrderByPaidAtDesc(UUID buyerUserId, StoreOrderStatus status);

    /** Orders paid with this email that no account has claimed yet. */
    @Query("SELECT o FROM StoreOrder o WHERE o.buyerUserId IS NULL AND o.status = :status "
            + "AND LOWER(o.buyerEmail) = LOWER(:email)")
    List<StoreOrder> findUnclaimedByEmail(@Param("email") String email, @Param("status") StoreOrderStatus status);

    boolean existsByProductIdAndStatus(UUID productId, StoreOrderStatus status);
}
