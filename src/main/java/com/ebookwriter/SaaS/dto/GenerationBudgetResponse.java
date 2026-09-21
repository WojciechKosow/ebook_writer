package com.ebookwriter.SaaS.dto;

/**
 * What the ebook-creation UI needs to describe generation as a <b>budget</b>, not
 * an order for a fixed number of pages.
 *
 * <p>The user no longer picks a page count: Scrivetta decides how much content a
 * complete ebook needs, and the final length is a result of generation. Credits
 * are the budget that pays for it (1 credit ≈ 1 generated page, an internal
 * billing unit — never surfaced as "N credits = N pages").
 *
 * @param minCredits        the smallest balance that may start a standard
 *                          generation (below this the UI shows "you need at least
 *                          N credits")
 * @param estimatedPagesLow  the low end of the orientational page range (~20)
 * @param estimatedPagesHigh the high end of the orientational page range (~30)
 * @param balance           the user's current credit balance
 * @param canGenerate       whether the balance is enough to start now
 */
public record GenerationBudgetResponse(
        int minCredits,
        int estimatedPagesLow,
        int estimatedPagesHigh,
        int balance,
        boolean canGenerate
) {
}
