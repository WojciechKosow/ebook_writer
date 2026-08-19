package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.EbookChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EbookChapterRepository extends JpaRepository<EbookChapter, UUID> {

    List<EbookChapter> findByEbookIdOrderByChapterNumberAsc(UUID ebookId);
}
