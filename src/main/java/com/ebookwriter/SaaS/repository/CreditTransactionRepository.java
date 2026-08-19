package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
}
