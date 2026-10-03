package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.BookDepth;

import java.util.List;

/**
 * What the ebook-creation form needs before a draft exists: the depth options
 * with a preliminary, brief-based length and credit estimate for each, and the
 * user's balance.
 *
 * <p>The user never picks a page count. They choose a {@link BookDepth}; Scrivetta
 * determines the length from the topic, the materials and that depth. These
 * figures are rough until materials are added and the book is planned — the draft's
 * {@code GET /api/ebooks/{id}/scope} gives the refined estimate.
 *
 * @param balance      the user's current credit balance
 * @param defaultDepth the depth used when the user selects none
 * @param options      one preliminary estimate per depth
 * @param maxPages     the per-book safety maximum (pages)
 */
public record GenerationBudgetResponse(int balance,
                                       BookDepth defaultDepth,
                                       List<ScopeEstimateDTO> options,
                                       int maxPages) {
}
