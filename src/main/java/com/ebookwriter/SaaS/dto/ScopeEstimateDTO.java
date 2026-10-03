package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.service.ebook.ScopeEstimate;

/**
 * One length estimate as the UI shows it. Every figure is an <b>estimate</b>:
 * the real length follows the content and may land outside the range.
 *
 * @param depth           the depth this estimate is for
 * @param label           display name of the depth ("Comprehensive")
 * @param description     what the depth means, in one line
 * @param pagesLow        low end of the estimated whole-book length
 * @param pagesHigh       high end of the estimated whole-book length
 * @param chaptersLow     low end of the estimated chapter count
 * @param chaptersHigh    high end of the estimated chapter count
 * @param creditsLow      estimated credit usage, low end (1 credit ≈ 1 final page)
 * @param creditsHigh     estimated credit usage, high end
 * @param requiredCredits credits needed to start this book (the high end), so it
 *                        is never wound down early for lack of credits
 * @param basis           what the estimate is based on (BRIEF, SOURCE_TEXT, KNOWLEDGE, BLUEPRINT)
 * @param sourcePages     the provided material, in pages of source text
 * @param capped          true when the content suggested more than the per-book maximum
 * @param aiAssessed      true when OpenAI's scope assessment shaped the range
 */
public record ScopeEstimateDTO(BookDepth depth,
                               String label,
                               String description,
                               int pagesLow,
                               int pagesHigh,
                               int chaptersLow,
                               int chaptersHigh,
                               int creditsLow,
                               int creditsHigh,
                               int requiredCredits,
                               ScopeEstimate.Basis basis,
                               int sourcePages,
                               boolean capped,
                               boolean aiAssessed) {

    public static ScopeEstimateDTO from(ScopeEstimate e) {
        return new ScopeEstimateDTO(e.depth(), e.depth().label(), e.depth().description(),
                e.pagesLow(), e.pagesHigh(), e.chaptersLow(), e.chaptersHigh(),
                e.pagesLow(), e.pagesHigh(), e.requiredCredits(), e.basis(), e.sourcePages(), e.capped(), e.aiAssessed());
    }
}
