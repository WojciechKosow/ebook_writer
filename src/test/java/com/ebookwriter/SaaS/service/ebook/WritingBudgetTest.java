package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ebook.WritingBudget.Decision;
import com.ebookwriter.SaaS.service.ebook.WritingBudget.PlannedWords;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credits are permission to continue, not an instruction to write more — and when
 * they run short the book is wound down to its planned ending, never cut off.
 */
class WritingBudgetTest {

    private static List<PlannedWords> chapters(int... words) {
        List<PlannedWords> list = new java.util.ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            list.add(new PlannedWords(i + 1, words[i]));
        }
        return list;
    }

    @Test
    void withEnoughCreditsEveryChapterIsWrittenAsPlanned() {
        Decision d = WritingBudget.decide(chapters(1000, 1200, 800), 0, 10_000);

        assertFalse(d.windDown());
        assertFalse(d.compressed());
        assertEquals(1200, d.targetFor(2));
    }

    @Test
    void passingTheSoftTargetNeverStopsABookThatCreditsCanStillCover() {
        // Earlier chapters ran long: 6,000 words written where ~4,000 were planned,
        // so the book is already past its soft target. Credits still cover the rest,
        // so the remaining chapters are written in full — nothing is truncated.
        Decision d = WritingBudget.decide(chapters(1000, 800), 6_000, 9_000);

        assertFalse(d.windDown());
        assertFalse(d.compressed());
        assertEquals(1000, d.targetFor(1));
        assertEquals(800, d.targetFor(2));
    }

    @Test
    void creditsDoNotInflateChapters() {
        // An enormous balance never raises a chapter above its plan.
        Decision d = WritingBudget.decide(chapters(900), 0, Integer.MAX_VALUE);
        assertEquals(900, d.targetFor(1));
    }

    @Test
    void slightlyShortCreditsTightenTheRestInsteadOfDroppingChapters() {
        Decision d = WritingBudget.decide(chapters(1000, 1000, 1000), 0, 2_400);

        assertFalse(d.windDown(), "80% of the plan fits — no chapter is dropped");
        assertTrue(d.compressed());
        int total = d.targetFor(1) + d.targetFor(2) + d.targetFor(3);
        assertTrue(total <= 2_400, "tightened chapters fit the remaining credits");
    }

    @Test
    void genuinelyShortCreditsWindDownAndAlwaysKeepThePlannedEnding() {
        // Five body chapters + a conclusion (6), but credits for only ~2,000 words.
        Decision d = WritingBudget.decide(chapters(1000, 1000, 1000, 1000, 1000, 800), 0, 2_000);

        assertTrue(d.windDown());
        assertFalse(d.isDeferred(6), "the book's planned ending is never deferred");
        assertTrue(d.targetFor(6) >= WritingBudget.MIN_CHAPTER_WORDS);
        assertTrue(d.isDeferred(5));
        // Deferral is contiguous from the tail of the body: no skipping and resuming.
        boolean seenDeferred = false;
        for (int n = 1; n <= 5; n++) {
            if (d.isDeferred(n)) {
                seenDeferred = true;
            } else {
                assertFalse(seenDeferred, "a kept chapter never follows a deferred one");
            }
        }
    }

    @Test
    void theLastRemainingChapterIsAlwaysWrittenEvenWhenCreditsAreGone() {
        Decision d = WritingBudget.decide(chapters(1200), 9_000, 9_000);

        assertFalse(d.isDeferred(1));
        assertEquals(WritingBudget.MIN_CHAPTER_WORDS, d.targetFor(1),
                "a short, complete ending (covered by the overdraft) beats no ending");
    }

    @Test
    void capacityAccountsForFrontMatterOpenersAndImages() {
        int capacity = WritingBudget.capacityWords(40, 5, 3);
        int expectedPages = 40 - EbookHtmlBuilder.FRONT_MATTER_PAGES - 5 - 3;
        assertEquals((int) Math.floor(expectedPages * ChapterGenerationService.WORDS_PER_PAGE
                * WritingBudget.SAFETY_FACTOR), capacity);
        assertEquals(Integer.MAX_VALUE, WritingBudget.capacityWords(0, 5, 3), "no ceiling → unbounded");
    }
}
