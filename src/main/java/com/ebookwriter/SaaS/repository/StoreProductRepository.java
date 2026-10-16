package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.StoreProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreProductRepository extends JpaRepository<StoreProduct, UUID> {

    List<StoreProduct> findByStoreIdOrderBySortOrderAscCreatedAtAsc(UUID storeId);

    List<StoreProduct> findByStoreIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(UUID storeId);

    Optional<StoreProduct> findByIdAndStoreId(UUID id, UUID storeId);

    boolean existsByStoreIdAndEbookId(UUID storeId, UUID ebookId);
}
