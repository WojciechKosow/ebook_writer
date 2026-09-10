package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EbookImageRepository extends JpaRepository<EbookImage, UUID> {

    List<EbookImage> findByEbookIdOrderByCreatedAtAsc(UUID ebookId);

    Optional<EbookImage> findByIdAndEbookId(UUID id, UUID ebookId);

    List<EbookImage> findByEbookIdAndPlacement(UUID ebookId, EbookImagePlacement placement);

    /** Assets assigned to a specific chapter (placement is kept in sync with the Markdown). */
    List<EbookImage> findByChapterId(UUID chapterId);
}
