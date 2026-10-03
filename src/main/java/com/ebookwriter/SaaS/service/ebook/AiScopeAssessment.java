package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.BookDepth;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * OpenAI's judgement of the book's length per depth (raw — before it is bounded
 * by {@link ScopeEstimator#combine}). Cached on the ebook as JSON.
 *
 * @param ranges        whole-book page range per depth
 * @param contentAmount small / medium / large / very_large
 * @param rationale     what drives the length, for the UI
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiScopeAssessment(Map<BookDepth, Range> ranges, String contentAmount, String rationale) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Range(int pagesLow, int pagesHigh) {
    }

    public Range rangeFor(BookDepth depth) {
        return ranges == null ? null : ranges.get(BookDepth.orDefault(depth));
    }
}
