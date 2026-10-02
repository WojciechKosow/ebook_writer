package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.BlueprintQuestion;
import com.ebookwriter.SaaS.entity.QuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BlueprintQuestionRepository extends JpaRepository<BlueprintQuestion, UUID> {

    List<BlueprintQuestion> findByEbookIdOrderBySortOrderAscCreatedAtAsc(UUID ebookId);

    Optional<BlueprintQuestion> findByIdAndEbookId(UUID id, UUID ebookId);

    long countByEbookIdAndStatus(UUID ebookId, QuestionStatus status);
}
