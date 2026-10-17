package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.LibraryAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LibraryAssetRepository extends JpaRepository<LibraryAsset, UUID> {

    List<LibraryAsset> findByUserIdOrderByLastUsedAtDesc(UUID userId);

    Optional<LibraryAsset> findByIdAndUserId(UUID id, UUID userId);

    Optional<LibraryAsset> findFirstByUserIdAndSha256(UUID userId, String sha256);

    List<LibraryAsset> findByExpiresAtBefore(LocalDateTime cutoff);

    List<LibraryAsset> findByUserIdAndExpiresAtIsNotNull(UUID userId);
}
