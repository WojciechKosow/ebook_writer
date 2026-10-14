package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.DownloadGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface DownloadGrantRepository extends JpaRepository<DownloadGrant, UUID> {

    Optional<DownloadGrant> findByTokenHash(String tokenHash);

    Optional<DownloadGrant> findByOrderId(UUID orderId);

    /**
     * Atomically consume one download. Returns 1 only when the grant is still
     * usable (not revoked, not expired, downloads left) — so two concurrent
     * requests can never exceed {@code maxDownloads}.
     */
    @Modifying
    @Query("UPDATE DownloadGrant g SET g.downloadsUsed = g.downloadsUsed + 1 "
            + "WHERE g.id = :id AND g.revokedAt IS NULL AND g.expiresAt > :now "
            + "AND g.downloadsUsed < g.maxDownloads")
    int consume(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
