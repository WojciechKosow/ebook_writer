package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<CreditTransaction> findByStripeReference(String stripeReference);

    boolean existsByStripeReferenceAndType(String stripeReference, CreditTransactionType type);

    Optional<CreditTransaction> findFirstByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, CreditTransactionType type);
}
