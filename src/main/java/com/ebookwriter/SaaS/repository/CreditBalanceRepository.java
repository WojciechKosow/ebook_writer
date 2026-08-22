package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.CreditBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreditBalanceRepository extends JpaRepository<CreditBalance, UUID> {

    /**
     * SELECT ... FOR UPDATE — locks the wallet row for the duration of the
     * transaction so two concurrent grants/spends can never both read the same
     * balance and race. This is the same proven approach the bossAi backend uses
     * for its wallet: mutate the returned <em>managed</em> entity and {@code save}
     * it. Crucially, unlike a bulk {@code @Modifying(clearAutomatically = true)}
     * update, this never clears the persistence context, so other entities loaded
     * in the same transaction (e.g. the PaymentOrder the Stripe webhook is
     * fulfilling) stay attached and their own saves don't collide on @Version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CreditBalance b where b.userId = :userId")
    Optional<CreditBalance> findForUpdate(@Param("userId") UUID userId);
}
