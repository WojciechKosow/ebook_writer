package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.entity.WritingQuality;
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

    /** The standard page price — the Better model's (premium model writes). */
    public int creditsPerPage() {
        return Math.max(1, creditProperties.getPricing().getCreditsPerPage());
    }

    /**
     * The page price for a book written at this quality: the Basic model is
     * cheaper (a lighter model writes from the premium model's briefs), the
     * Better model is the standard price.
     */
    public int creditsPerPage(WritingQuality quality) {
        return WritingQuality.orStored(quality) == WritingQuality.BASIC
                ? Math.max(1, creditProperties.getPricing().getBasicCreditsPerPage())
                : creditsPerPage();
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

    /** Credits for {@code pages} finished pages of text at the standard rate. */
    public int pageCredits(int pages) {
        return pageCredits(pages, creditsPerPage());
    }

    /** Credits for {@code pages} finished pages of text at {@code creditsPerPage}. */
    public int pageCredits(int pages, int creditsPerPage) {
        return Math.max(0, pages) * Math.max(1, creditsPerPage);
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
        return estimateLow(pagesLow, creditsPerPage());
    }

    /** As {@link #estimateLow(int)} at {@code creditsPerPage}. */
    public int estimateLow(int pagesLow, int creditsPerPage) {
        return pageCredits(pagesLow, creditsPerPage) + (imagesEnabled() ? creditsPerCover() : 0);
    }

    /** High end of a book's estimated cost: the estimate's high page count plus the full image reserve. */
    public int estimateHigh(int pagesHigh) {
        return estimateHigh(pagesHigh, creditsPerPage());
    }

    /** As {@link #estimateHigh(int)} at {@code creditsPerPage}. */
    public int estimateHigh(int pagesHigh, int creditsPerPage) {
        return pageCredits(pagesHigh, creditsPerPage) + imageReserveCredits();
    }

    /**
     * What a user must hold to start a book: the low end of its estimate plus the
     * image reserve. Starting never needs the high end — the book is written
     * within whatever the credits allow and billed only for what it produces.
     */
    public int minimumToStart(int pagesLow, int creditsPerPage) {
        return pageCredits(Math.max(1, pagesLow), creditsPerPage) + imageReserveCredits();
    }

    /** The bounded generation overdraft, in credits. */
    public int overdraftCredits() {
        return pageCredits(Math.max(0, creditProperties.getMaxOverdraft()));
    }
}
