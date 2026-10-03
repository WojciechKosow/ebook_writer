package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.BookDepth;
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

    @Test
    void droppingChaptersToFitAlwaysKeepsTheConcludingChapter() {
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 2),
                new PlannedChapter("B", "", 2),
                new PlannedChapter("C", "", 2),
                new PlannedChapter("D", "", 2),
                new PlannedChapter("Conclusion", "", 2));

        List<PlannedChapter> clamped = BookPlanningService.clampToBudget(plan, 3);

        assertEquals("Conclusion", clamped.get(clamped.size() - 1).title(),
                "the book keeps its ending even under a tight ceiling");
    }

    private static ScopeEstimate estimate(int low, int high) {
        return new ScopeEstimate(BookDepth.STANDARD, low, high, 5, 8, ScopeEstimate.Basis.BRIEF, 0, false, false);
    }

    @Test
    void aPlanLongerThanTheEstimateIsKeptExactlyAsDesigned() {
        // Estimated 27–41 pages; the planner decided the subject needs 70. The
        // estimate is orientation, not a limit, so the plan stands.
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 25),
                new PlannedChapter("B", "", 25),
                new PlannedChapter("C", "", 20));

        assertEquals(plan, BookPlanningService.limitRunaway(plan, estimate(27, 41)));
    }

    @Test
    void onlyAPlannerRunawayIsPulledBackAndNoChapterIsDropped() {
        // Estimated 27–41 pages, the model planned 300 — the "keeps expanding" failure.
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 100),
                new PlannedChapter("B", "", 100),
                new PlannedChapter("Conclusion", "", 100));

        List<PlannedChapter> limited = BookPlanningService.limitRunaway(plan, estimate(27, 41));

        assertEquals(3, limited.size(), "every planned topic survives");
        int total = totalPages(limited);
        int landing = (int) Math.ceil((41 - EbookHtmlBuilder.FRONT_MATTER_PAGES) * BookPlanningService.RUNAWAY_LANDING);
        assertTrue(Math.abs(total - landing) <= 2, "pulled back to ~" + landing + ", got " + total);
        assertTrue(total > 41, "still allowed past the estimate");
    }

    @Test
    void aShortPlanIsNeverPadded() {
        List<PlannedChapter> plan = List.of(
                new PlannedChapter("A", "", 5),
                new PlannedChapter("B", "", 5));

        assertEquals(10, totalPages(BookPlanningService.limitRunaway(plan, estimate(27, 41))));
    }
}
