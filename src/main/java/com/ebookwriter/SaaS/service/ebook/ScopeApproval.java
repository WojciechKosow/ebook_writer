package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;

/**
 * When a generation must stop and ask the user. The length of a book is a result
 * of its content, so a book may turn out longer than estimated — but it must not
 * silently become a much longer (and more expensive) book, nor be silently cut to
 * fit the credits. Instead it pauses in {@code AWAITING_APPROVAL} and the user
 * decides: continue at the new length, keep it within the agreed length, or (after
 * planning) cancel.
 *
 * <p>Small drift is normal and never asks: only a plan more than
 * {@link #PLAN_TOLERANCE}, or a writing projection more than
 * {@link #WRITING_TOLERANCE}, past the agreed length does — or a book the credits
 * genuinely can't cover. Pure; unit-tested.
 */
public final class ScopeApproval {

    private ScopeApproval() {
    }

    public static final String STAGE_PLAN = "PLAN";
    public static final String STAGE_WRITING = "WRITING";

    /** A plan may exceed the agreed length by this much without asking. */
    static final double PLAN_TOLERANCE = 0.10;

    /** While writing, the projected length may exceed the agreed length by this much without asking. */
    static final double WRITING_TOLERANCE = 0.15;

    /**
     * True when the plan is clearly longer than agreed, or needs a bigger credit
     * hold than was reserved ({@code holdNeeded}, from {@link WritingBudget#holdFor}).
     */
    public static boolean planNeedsApproval(int plannedPages, int holdNeeded, Ebook ebook) {
        if (ebook.isFitToBudget() || plannedPages <= 0) {
            return false;
        }
        return beyond(plannedPages, ebook.getApprovedPages(), PLAN_TOLERANCE)
                || (ebook.getPageBudget() > 0 && holdNeeded > ebook.getPageBudget());
    }

    /** True when the book, as it is being written, is heading clearly past the agreed length. */
    public static boolean writingNeedsApproval(int projectedPages, Ebook ebook) {
        return !ebook.isFitToBudget() && beyond(projectedPages, ebook.getApprovedPages(), WRITING_TOLERANCE);
    }

    /**
     * Whole-book pages the finished book is heading for: the words already written
     * plus the planned words still to write, at the calibrated words per page,
     * plus front matter.
     */
    public static int projectedPages(int wordsWritten, int plannedWordsRemaining) {
        double words = Math.max(0, wordsWritten) + Math.max(0, plannedWordsRemaining);
        return EbookHtmlBuilder.FRONT_MATTER_PAGES
                + (int) Math.ceil(words / ChapterGenerationService.WORDS_PER_PAGE);
    }

    /** Extra credits to add to the hold to reach {@code holdNeeded}; zero when it already does. */
    public static int extraHold(Integer holdNeeded, int pageBudget) {
        return holdNeeded == null ? 0 : Math.max(0, holdNeeded - Math.max(0, pageBudget));
    }

    private static boolean beyond(int pages, Integer approved, double tolerance) {
        return approved != null && approved > 0 && pages > approved * (1 + tolerance);
    }
}
