package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.BlueprintStatus;
import com.ebookwriter.SaaS.entity.BookBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookBlueprintRepository extends JpaRepository<BookBlueprint, UUID> {

    Optional<BookBlueprint> findByEbookId(UUID ebookId);

    /**
     * Atomically claim a book's blueprint for a build (→ BUILDING_BLUEPRINT), from
     * one of the {@code claimable} states or from a build stuck since before
     * {@code staleBefore}. Exactly one concurrent caller wins (returns 1), so a
     * double click never pays for two builds.
     */
    @Modifying
    @Transactional
    @Query("""
            update BookBlueprint b
               set b.status = :building, b.startedAt = :now, b.errorMessage = null,
                   b.generation = b.generation + 1, b.updatedAt = :now
             where b.ebook.id = :ebookId
               and (b.status in :claimable or (b.status = :building and b.startedAt < :staleBefore))
            """)
    int claimForBuilding(@Param("ebookId") UUID ebookId,
                         @Param("claimable") Collection<BlueprintStatus> claimable,
                         @Param("staleBefore") LocalDateTime staleBefore,
                         @Param("building") BlueprintStatus building,
                         @Param("now") LocalDateTime now);
}
