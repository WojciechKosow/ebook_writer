package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.KnowledgeSource;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, UUID> {

    List<KnowledgeSource> findByEbookIdOrderByCreatedAtAsc(UUID ebookId);

    Optional<KnowledgeSource> findByIdAndEbookId(UUID id, UUID ebookId);

    Optional<KnowledgeSource> findFirstByEbookIdAndSourceType(UUID ebookId, KnowledgeSourceType type);

    boolean existsByEbookIdAndRawSha256(UUID ebookId, String rawSha256);

    long countByEbookId(UUID ebookId);
}
