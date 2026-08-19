package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.Ebook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
