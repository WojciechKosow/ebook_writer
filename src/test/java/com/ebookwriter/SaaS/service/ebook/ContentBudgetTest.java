package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selected target length is a soft content budget: it scales the plan
 * (chapters, depth, practice material) and gives a tolerant soft limit — never a
 * hard page cap.
 */
class ContentBudgetTest {

    @Test
    void aLongerTargetPlansASubstantiallyBiggerBook() {
        ContentBudget thirty = ContentBudget.forTarget(30);
        ContentBudget fifty = ContentBudget.forTarget(50);
        ContentBudget hundred = ContentBudget.forTarget(100);

        assertTrue(fifty.contentTarget() > thirty.contentTarget());
        assertTrue(fifty.maxChapters() > thirty.maxChapters());
        assertTrue(fifty.exercisesPerChapter() >= thirty.exercisesPerChapter());
        assertTrue(hundred.minChapters() > fifty.minChapters());
    }

    @Test
    void contentTargetExcludesFrontMatter() {
        assertEquals(30 - EbookHtmlBuilder.FRONT_MATTER_PAGES, ContentBudget.forTarget(30).contentTarget());
    }

    @Test
    void theSoftLimitToleratesANaturalOvershoot() {
        ContentBudget b = ContentBudget.forTarget(30);
        // 28 content pages * 1.25 → 35: a book landing at 32–35 is accepted as designed.
        assertEquals(35, b.softLimit(0));
        assertTrue(b.rangeLow() < 30 && b.rangeHigh() > 30);
    }

    @Test
    void aPromisedStructureRaisesTheSoftLimitRatherThanBeingCompressed() {
        ContentBudget b = ContentBudget.forTarget(20);
        int fourteenDays = BookPlanningService.structuralMinimumPages(new StructureRequirement(14, "day"));
        assertTrue(fourteenDays > b.softLimit(0));
        assertEquals(fourteenDays, b.softLimit(fourteenDays),
                "14 days at minimum depth outweighs the 20-page number");
    }

    @Test
    void tinyTargetsAreLiftedToTheMinimum() {
        assertEquals(ContentBudget.MIN_TARGET_PAGES, ContentBudget.forTarget(3).targetPages());
    }
}
