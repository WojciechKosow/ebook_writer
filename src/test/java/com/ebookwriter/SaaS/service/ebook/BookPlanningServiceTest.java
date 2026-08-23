package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan-clamp is the hard ceiling that stops the model planning (and us
 * paying for) more pages than the user reserved.
 */
class BookPlanningServiceTest {

    private static int totalPages(List<PlannedChapter> chapters) {
        return chapters.stream().mapToInt(PlannedChapter::approxPages).sum();
    }

    @Test
    void leavesPlanUntouchedWhenItAlreadyFitsBudget() {
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 3),
                new PlannedChapter("B", "", 2));

        List<PlannedChapter> clamped = BookPlanningService.clampToBudget(plan, 10);

        assertEquals(2, clamped.size());
        assertEquals(5, totalPages(clamped));
    }

    @Test
    void scalesDownProportionallyAndNeverBreachesBudget() {
        // Model planned 180 pages against a 60-page budget (the 5-asked/18-made bug).
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 60),
                new PlannedChapter("B", "", 60),
                new PlannedChapter("C", "", 60));

        List<PlannedChapter> clamped = BookPlanningService.clampToBudget(plan, 60);

        assertTrue(totalPages(clamped) <= 60, "clamped total must not exceed the budget");
        assertEquals(3, clamped.size(), "chapters are kept, only shortened");
        assertTrue(clamped.stream().allMatch(c -> c.approxPages() >= 1), "every chapter keeps at least one page");
    }

    @Test
    void everyChapterKeepsAtLeastOnePage() {
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 100),
                new PlannedChapter("B", "", 1));

        List<PlannedChapter> clamped = BookPlanningService.clampToBudget(plan, 50);

        assertTrue(totalPages(clamped) <= 50);
        assertTrue(clamped.stream().allMatch(c -> c.approxPages() >= 1));
    }

    @Test
    void dropsExtraChaptersWhenBudgetCannotHoldOnePageEach() {
        // 5 chapters but only a 3-page budget: keep 3, one page each.
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 2),
                new PlannedChapter("B", "", 2),
                new PlannedChapter("C", "", 2),
                new PlannedChapter("D", "", 2),
                new PlannedChapter("E", "", 2));

        List<PlannedChapter> clamped = BookPlanningService.clampToBudget(plan, 3);

        assertEquals(3, clamped.size());
        assertTrue(totalPages(clamped) <= 3);
    }
}
