package com.ebookwriter.SaaS.dto;

/**
 * The public credit-cost policy, so the UI can explain what credits buy. Costs
 * only — no provider prices, margins or model details.
 *
 * @param creditsPerPage    credits per finished page of a generated book
 * @param creditsPerImage   credits per AI illustration
 * @param creditsPerCover   credits per AI cover visual (with a book, or regenerated)
 * @param maxImagesPerBook  the most AI illustrations one book gets (0 = images off)
 * @param monthlyCredits    credits included in each PRO billing period
 */
public record CreditPricingResponse(int creditsPerPage,
                                    int creditsPerImage,
                                    int creditsPerCover,
                                    int maxImagesPerBook,
                                    int monthlyCredits) {
}
