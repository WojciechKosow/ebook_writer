package com.ebookwriter.SaaS.dto.knowledge;

import com.ebookwriter.SaaS.entity.KnowledgeSourceStatus;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One uploaded source as the UI sees it (no content). {@code skipped} is a sample, capped. */
public record KnowledgeSourceDTO(UUID id, KnowledgeSourceType sourceType, String filename, long sizeBytes,
                                 KnowledgeSourceStatus status, String errorMessage, int documentCount,
                                 int skippedCount, long extractedChars, List<SkippedFile> skipped,
                                 LocalDateTime createdAt) {
}
