package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.entity.KnowledgeSource;
import com.ebookwriter.SaaS.entity.KnowledgeSourceStatus;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read access to a book's stored, normalised documents by the same {@code ref}
 * that BookKnowledge and the blueprint cite (assigned exactly as during
 * processing, see {@link KnowledgeChunker#assignRefs}). Used by the writing
 * stage to quote a chapter's own source files — never the whole upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentStore {

    private final KnowledgeSourceRepository sourceRepository;

    /** ref → document, for every readable document of the book. */
    public Map<String, NormalizedDocument> documentsByRef(UUID ebookId) {
        List<AnalysisDocument> all = new ArrayList<>();
        for (KnowledgeSource s : sourceRepository.findByEbookIdOrderByCreatedAtAsc(ebookId)) {
            if (s.getStatus() == KnowledgeSourceStatus.FAILED || s.getDocumentsJson() == null) continue;
            try {
                List<NormalizedDocument> docs = KnowledgeAssembler.MAPPER.readValue(s.getDocumentsJson(),
                        new TypeReference<List<NormalizedDocument>>() {
                        });
                for (NormalizedDocument d : docs) all.add(new AnalysisDocument(s.getOriginalFilename(), s.getSourceType(), d));
            } catch (Exception e) {
                log.warn("Stored documents of source {} are unreadable: {}", s.getId(), e.getMessage());
            }
        }
        Map<String, NormalizedDocument> out = new LinkedHashMap<>();
        KnowledgeChunker.assignRefs(all).forEach((ref, ad) -> out.put(ref, ad.document()));
        return out;
    }
}
