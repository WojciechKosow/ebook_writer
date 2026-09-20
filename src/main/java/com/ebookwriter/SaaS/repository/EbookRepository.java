package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EbookRepository extends JpaRepository<Ebook, UUID> {

    Optional<Ebook> findByIdAndUserId(UUID id, UUID userId);

    List<Ebook> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** The owning user's id, without triggering a lazy load of the User. */
    @Query("select e.user.id from Ebook e where e.id = :id")
    Optional<UUID> findUserIdById(UUID id);

    /**
     * Atomically claim a DRAFT for starting by moving it to PENDING. Returns the
     * number of rows changed: exactly one caller wins the DRAFT -> PENDING
     * transition, so concurrent or duplicated {@code start} requests (a double
     * click, a retry, a refresh) can never both reserve a credit hold. A return
     * of 0 means the ebook was not a DRAFT — it has already been started.
     */
    @Modifying
    @Transactional
    @Query("update Ebook e set e.status = :pending where e.id = :id and e.status = :draft")
    int claimForStart(@Param("id") UUID id,
                      @Param("draft") EbookStatus draft,
                      @Param("pending") EbookStatus pending);

    /**
     * Atomically claim the final billing (true-up) for an ebook. Returns 1 the
     * first time and 0 afterwards, so the charge/refund is applied exactly once
     * however many times settlement is attempted (a retried async worker, a
     * re-run generation). Idempotent billing lives on this single flip.
     */
    @Modifying
    @Transactional
    @Query("update Ebook e set e.creditsReconciled = true where e.id = :id and e.creditsReconciled = false")
    int markReconciled(@Param("id") UUID id);
}
