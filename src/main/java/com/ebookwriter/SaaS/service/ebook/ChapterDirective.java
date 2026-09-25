package com.ebookwriter.SaaS.service.ebook;

import java.util.List;

/**
 * How one chapter should be written, decided by the orchestrator from the plan
 * and the credit budget (see {@link WritingBudget}).
 *
 * @param targetWords     the soft length to aim for
 * @param tight           true when credits are tight: the length is a real limit,
 *                        not just a guide (the chapter must still end cleanly)
 * @param finalChapter    true when this chapter ends the book
 * @param omittedChapters titles of planned chapters left out of this book to end it
 *                        naturally within the user's credits — the ending must not
 *                        promise or reference them
 */
public record ChapterDirective(int targetWords, boolean tight, boolean finalChapter,
                               List<String> omittedChapters) {

    public ChapterDirective {
        omittedChapters = omittedChapters == null ? List.of() : List.copyOf(omittedChapters);
    }

    /** True when this ending was re-planned because the credits could not cover the whole outline. */
    public boolean windDown() {
        return finalChapter && !omittedChapters.isEmpty();
    }
}
