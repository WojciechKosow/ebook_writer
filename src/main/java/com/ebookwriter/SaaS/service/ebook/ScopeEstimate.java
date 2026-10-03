package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.BookDepth;

/**
 * Scrivetta's estimate of how long a book will be, made <em>before</em> any text
 * is written from the selected depth, the brief and the materials (see
 * {@link ScopeEstimator}).
 *
 * <p>It is an <b>estimate, not a target</b>: nothing in generation tries to hit
 * it. It is used to tell the user the expected length and credit usage, to gate
 * the start on having enough credits for the expected book (so a book is never
 * silently cut to fit the balance), and as orientation for the planner. The real
 * length is a result of the content, and may land outside this range.
 *
 * @param depth         the depth the estimate was made for
 * @param pagesLow      low end of the expected whole-book length (front matter included)
 * @param pagesHigh     high end of the expected whole-book length
 * @param chaptersLow   low end of the expected chapter count
 * @param chaptersHigh  high end of the expected chapter count
 * @param basis         what the estimate is based on (how much Scrivetta knows yet)
 * @param sourcePages   the provided source material, in pages of source text (0 = none)
 * @param capped        true when the content suggested more than the per-book
 *                      safety maximum, so the range was capped at it
 * @param aiAssessed    true when OpenAI's scope assessment shaped the range
 */
public record ScopeEstimate(BookDepth depth,
                            int pagesLow,
                            int pagesHigh,
                            int chaptersLow,
                            int chaptersHigh,
                            Basis basis,
                            int sourcePages,
                            boolean capped,
                            boolean aiAssessed) {

    /** What the estimate is based on, from least to most informed. */
    public enum Basis {
        /** Only the brief (topic, goal, instructions); refined when the book is planned. */
        BRIEF,
        /** The brief plus source text pasted into it. */
        SOURCE_TEXT,
        /** The author's analysed materials (BookKnowledge). */
        KNOWLEDGE,
        /** The author's approved chapter structure (Book Blueprint) and its knowledge. */
        BLUEPRINT
    }

    /** The middle of the range — orientation for the planner, never a goal. */
    public int midpoint() {
        return (pagesLow + pagesHigh) / 2;
    }

    /**
     * Credits a user should hold to generate the expected book without it being
     * wound down early: the high end of the estimate (1 credit ≈ 1 page). The
     * user is still billed only for the pages actually rendered.
     */
    public int requiredCredits() {
        return Math.max(1, pagesHigh);
    }
}
