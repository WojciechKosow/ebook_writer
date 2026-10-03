package com.ebookwriter.SaaS.service.ebook;

/**
 * Thrown by planning when the book Scrivetta planned needs more credits than the
 * user's reserved hold covers. The book is <b>not</b> cut down to fit: the
 * orchestrator refunds the hold, returns the book to a DRAFT and tells the user
 * how many credits the planned book needs, so they can top up (or choose a
 * lighter depth) and generate again.
 */
public class PlanExceedsCreditsException extends RuntimeException {

    private final int plannedPages;
    private final int availableCredits;

    public PlanExceedsCreditsException(int plannedPages, int availableCredits) {
        super(("Scrivetta planned this book at about %d pages, which needs about %d credits — your credits "
                + "cover about %d. Nothing was charged. Add credits (or choose a lighter depth) and generate again.")
                .formatted(plannedPages, plannedPages, Math.max(0, availableCredits)));
        this.plannedPages = plannedPages;
        this.availableCredits = availableCredits;
    }

    public int getPlannedPages() {
        return plannedPages;
    }

    public int getAvailableCredits() {
        return availableCredits;
    }
}
