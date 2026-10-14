package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    Optional<Store> findByOwnerId(UUID ownerId);

    Optional<Store> findBySlug(String slug);

    Optional<Store> findByStripeAccountId(String stripeAccountId);

    boolean existsBySlug(String slug);
}
