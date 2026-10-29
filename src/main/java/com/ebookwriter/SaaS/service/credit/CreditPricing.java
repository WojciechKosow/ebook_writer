package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The deterministic credit-cost policy: how many credits each paid AI operation
 * costs. The single place where pages and images become credits — the
 * generation engine measures a book in pages, and everything the user sees or
 * pays goes through here.
 *
 * <p>Costs come from {@link CreditProperties.Pricing} (see docs/PRICING.md for
 * how they were derived from the actual models). They are deliberately simple
 * and predictable: a book is billed for its final pages, the illustrations
 * actually generated and its AI cover — never for retries, failed steps or
 * work that was thrown away.
 */
@Component
@RequiredArgsConstructor
public class CreditPricing {

    private final CreditProperties creditProperties;
    private final OpenAiProperties openAiProperties;

    public int creditsPerPage() {
        return Math.max(1, creditProperties.getPricing().getCreditsPerPage());
    }

    public int creditsPerImage() {
        return Math.max(0, creditProperties.getPricing().getCreditsPerImage());
    }

    public int creditsPerCover() {
        return Math.max(0, creditProperties.getPricing().getCreditsPerCover());
    }

    /** Whether generations produce AI images (and so reserve credits for them). */
    public boolean imagesEnabled() {
        return openAiProperties.isEnabled() && openAiProperties.isConfigured();
    }

    /** The most AI illustrations one book may get (the image planner's cap). */
    public int maxImagesPerBook() {
        return imagesEnabled() ? Math.max(0, openAiProperties.getMaxImagesPerBook()) : 0;
    }

    /** Credits for {@code pages} finished pages of text. */
    public int pageCredits(int pages) {
        return Math.max(0, pages) * creditsPerPage();
    }

    /**
     * Credits reserved up front for a book's AI images: the most illustrations it
     * may get plus the cover. Trued up to the images actually generated.
     */
    public int imageReserveCredits() {
        if (!imagesEnabled()) {
            return 0;
        }
        return maxImagesPerBook() * creditsPerImage() + creditsPerCover();
    }

    /**
     * What a finished book costs: its rendered pages at the book's page rate,
     * plus each AI illustration (at most the per-book cap) and the AI cover, if
     * one was generated.
     */
    public int generationCharge(int pages, int creditsPerPage, int aiIllustrations, boolean aiCover) {
        int images = Math.min(Math.max(0, aiIllustrations), Math.max(0, openAiProperties.getMaxImagesPerBook()));
        return Math.max(0, pages) * Math.max(1, creditsPerPage)
                + images * creditsPerImage() + (aiCover ? creditsPerCover() : 0);
    }

    /** Low end of a book's estimated cost: the estimate's low page count and a cover. */
    public int estimateLow(int pagesLow) {
        return pageCredits(pagesLow) + (imagesEnabled() ? creditsPerCover() : 0);
    }

    /**
     * High end of a book's estimated cost — also what a user must hold to start
     * it: the estimate's high page count plus the full image reserve.
     */
    public int estimateHigh(int pagesHigh) {
        return pageCredits(pagesHigh) + imageReserveCredits();
    }

    /** The bounded generation overdraft, in credits. */
    public int overdraftCredits() {
        return pageCredits(Math.max(0, creditProperties.getMaxOverdraft()));
    }
}
