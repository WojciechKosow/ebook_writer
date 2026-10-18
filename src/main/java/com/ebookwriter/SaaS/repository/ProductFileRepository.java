package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.ProductFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductFileRepository extends JpaRepository<ProductFile, UUID> {

    /** The product's current files, in display order. */
    List<ProductFile> findByProductIdAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(UUID productId);

    List<ProductFile> findByProductIdInAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(Collection<UUID> productIds);

    Optional<ProductFile> findByIdAndProductIdAndRemovedAtIsNull(UUID id, UUID productId);

    long countByProductIdAndRemovedAtIsNull(UUID productId);
}
