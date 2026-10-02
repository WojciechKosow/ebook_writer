package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;

/**
 * A stored document on its way into the analysis, with the upload it came from.
 *
 * @param origin     the uploaded source's filename ("my-shop.zip", "user-notes")
 * @param sourceType the uploaded source's type
 */
public record AnalysisDocument(String origin, KnowledgeSourceType sourceType, NormalizedDocument document) {
}
