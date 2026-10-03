package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.BookDepth;

import java.util.List;

/**
 * A draft's scope, for the "Generate" step: the selected depth, Scrivetta's
 * length and credit estimate for it (from the brief, the materials and the
 * blueprint), the same estimate for the other depths, and whether the user holds
 * enough credits to start. Estimates only — the real length is a result.
 *
 * @param depth           the selected depth
 * @param estimate        the estimate for the selected depth
 * @param options         estimates for every depth (to compare before switching)
 * @param balance         the user's current credit balance
 * @param requiredCredits credits needed to start: the estimate's high end, or the
 *                        size of a plan that already came out larger
 * @param canGenerate     whether the balance covers {@code requiredCredits}
 * @param plannedPages    the size of an earlier plan that needed more credits than
 *                        the user had (null when there is none)
 * @param maxPages        the per-book safety maximum
 */
public record BookScopeResponse(BookDepth depth,
                                ScopeEstimateDTO estimate,
                                List<ScopeEstimateDTO> options,
                                int balance,
                                int requiredCredits,
                                boolean canGenerate,
                                Integer plannedPages,
                                int maxPages) {
}
