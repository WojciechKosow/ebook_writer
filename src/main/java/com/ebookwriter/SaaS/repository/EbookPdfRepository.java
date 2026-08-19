package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.EbookPdf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface EbookPdfRepository extends JpaRepository<EbookPdf, UUID> {
}
