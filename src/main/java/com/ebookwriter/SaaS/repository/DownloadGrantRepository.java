package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.DownloadGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DownloadGrantRepository extends JpaRepository<DownloadGrant, UUID> {

    Optional<DownloadGrant> findByTokenHash(String tokenHash);

    Optional<DownloadGrant> findByOrderId(UUID orderId);

    /** The grant, locked for this transaction — per-file download counts are read and written under it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM DownloadGrant g WHERE g.id = :id")
    Optional<DownloadGrant> lockById(@Param("id") UUID id);
}
