package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
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
public interface BookKnowledgeRepository extends JpaRepository<BookKnowledge, UUID> {

    Optional<BookKnowledge> findByEbookId(UUID ebookId);

    /**
     * Atomically claim a book's knowledge for a processing run: moves it to
     * PROCESSING when it is in one of the {@code claimable} states, or when a
     * previous run has been stuck since before {@code staleBefore} (e.g. the server
     * restarted mid-run). Exactly one concurrent caller wins (returns 1), so a
     * double click can never start — and pay for — two runs.
     */
    @Modifying
    @Transactional
    @Query("""
            update BookKnowledge k
               set k.status = :processing, k.startedAt = :now, k.errorMessage = null,
                   k.processingRuns = k.processingRuns + 1, k.updatedAt = :now
             where k.ebook.id = :ebookId
               and (k.status in :claimable or (k.status in :running and k.startedAt < :staleBefore))
            """)
    int claimForProcessing(@Param("ebookId") UUID ebookId,
                           @Param("claimable") Collection<KnowledgeStatus> claimable,
                           @Param("running") Collection<KnowledgeStatus> running,
                           @Param("staleBefore") LocalDateTime staleBefore,
                           @Param("processing") KnowledgeStatus processing,
                           @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update BookKnowledge k set k.status = :status, k.updatedAt = :now where k.ebook.id = :ebookId")
    int updateStatus(@Param("ebookId") UUID ebookId,
                     @Param("status") KnowledgeStatus status,
                     @Param("now") LocalDateTime now);

    /** Atomically move KNOWLEDGE_READY → READY_FOR_BLUEPRINT; 0 if it was not ready. */
    @Modifying
    @Transactional
    @Query("""
            update BookKnowledge k set k.status = :next, k.updatedAt = :now
             where k.ebook.id = :ebookId and k.status = :ready
            """)
    int markReadyForBlueprint(@Param("ebookId") UUID ebookId,
                              @Param("ready") KnowledgeStatus ready,
                              @Param("next") KnowledgeStatus next,
                              @Param("now") LocalDateTime now);
}
