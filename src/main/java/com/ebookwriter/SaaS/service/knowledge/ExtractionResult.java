package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;

import java.util.List;

/**
 * What extraction produced for one upload. {@code error} is set when nothing
 * usable came out (the source is then stored as FAILED, with the reason shown
 * to the author); individual skipped files never fail the whole source.
 */
public record ExtractionResult(List<NormalizedDocument> documents, List<SkippedFile> skipped, String error) {

    public static ExtractionResult failed(String error) {
        return new ExtractionResult(List.of(), List.of(), error);
    }

    public long totalChars() {
        return documents.stream().mapToLong(NormalizedDocument::chars).sum();
    }
}
