package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.CreditBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CreditBalanceRepository extends JpaRepository<CreditBalance, UUID> {

    /**
     * Atomically deduct credits only if the balance covers them. Returns the
     * number of rows updated: 1 = success, 0 = insufficient funds (or the row
     * didn't exist). This is the guard against overspending under concurrency
     * and against a negative balance.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
           update CreditBalance b
           set b.balance = b.balance - :amount
           where b.userId = :userId and b.balance >= :amount
           """)
    int deductIfEnough(@Param("userId") UUID userId, @Param("amount") int amount);

    /** Atomically add credits. Returns rows updated (1 if the wallet exists). */
    @Modifying(clearAutomatically = true)
    @Query("""
           update CreditBalance b
           set b.balance = b.balance + :amount
           where b.userId = :userId
           """)
    int increment(@Param("userId") UUID userId, @Param("amount") int amount);
}
