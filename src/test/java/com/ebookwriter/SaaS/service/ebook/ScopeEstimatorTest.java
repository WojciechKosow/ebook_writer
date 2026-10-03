package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.BookDepth;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.ebookwriter.SaaS.entity.BookDepth.COMPREHENSIVE;
import static com.ebookwriter.SaaS.entity.BookDepth.QUICK;
import static com.ebookwriter.SaaS.entity.BookDepth.STANDARD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Length is estimated from depth AND material — never from a typed page count,
 * and never as a plain {@code sourcePages × k}.
 */
class ScopeEstimatorTest {

    private static final int MAX = 400;
    private static final long PAGE = (long) ScopeEstimator.SOURCE_CHARS_PER_PAGE;

    private static ScopeEstimate brief(BookDepth depth) {
        return ScopeEstimator.estimate(ScopeEstimator.Signals.brief(depth, 150, 0, 0), MAX);
    }

    /** A knowledge-based book: {@code pages} of material, {@code units} extracted knowledge items. */
    private static ScopeEstimate materials(BookDepth depth, int pages, int units,
                                           List<ScopeEstimator.ChapterSignal> chapters) {
        return ScopeEstimator.estimate(new ScopeEstimator.Signals(depth, true, 300, 0,
                pages * PAGE, units, chapters), MAX);
    }

    private static List<ScopeEstimator.ChapterSignal> chapters(int n, int topics, int points, int refs) {
        return Collections.nCopies(n, new ScopeEstimator.ChapterSignal(topics, points, refs, 2));
    }

    @Test
    void depthOrdersTheScopeForABriefOnlyBook() {
        ScopeEstimate quick = brief(QUICK);
        ScopeEstimate standard = brief(STANDARD);
        ScopeEstimate comprehensive = brief(COMPREHENSIVE);

        assertTrue(quick.pagesHigh() < standard.pagesHigh());
        assertTrue(standard.pagesHigh() < comprehensive.pagesHigh());
        assertEquals(ScopeEstimate.Basis.BRIEF, standard.basis());
        assertTrue(quick.pagesLow() >= 10 && quick.pagesHigh() <= 30, "quick: " + quick);
        assertTrue(comprehensive.pagesLow() >= 40, "comprehensive: " + comprehensive);
    }

    @Test
    void theRangeIsARangeNotAPoint() {
        ScopeEstimate e = brief(STANDARD);
        assertTrue(e.pagesLow() < e.pagesHigh());
        assertTrue(e.chaptersLow() < e.chaptersHigh());
        assertEquals(e.pagesHigh(), e.requiredCredits(), "credits are needed for the high end");
    }

    @Test
    void fivePagesOfMaterialDoNotBecomeAHugeBookEvenAtComprehensive() {
        ScopeEstimate e = materials(COMPREHENSIVE, 5, 10, chapters(5, 2, 3, 2));

        assertTrue(e.pagesHigh() <= 50, "small material, comprehensive: " + e);
        assertEquals(ScopeEstimate.Basis.BLUEPRINT, e.basis());
    }

    @Test
    void largeHighQualityMaterialIsNotSummarisedIntoAShortBook() {
        ScopeEstimate standard = materials(STANDARD, 150, 90, chapters(12, 4, 6, 6));
        ScopeEstimate comprehensive = materials(COMPREHENSIVE, 150, 90, chapters(12, 4, 6, 6));

        assertTrue(standard.pagesLow() >= 60, "150 pages of material, standard: " + standard);
        assertTrue(comprehensive.pagesLow() >= 120, "150 pages of material, comprehensive: " + comprehensive);
    }

    @Test
    void largeMaterialAtQuickIsBiggerThanSmallMaterialButSelective() {
        ScopeEstimate largeQuick = materials(QUICK, 150, 90, chapters(12, 4, 6, 6));
        ScopeEstimate smallQuick = materials(QUICK, 5, 10, chapters(5, 2, 3, 2));
        ScopeEstimate largeComprehensive = materials(COMPREHENSIVE, 150, 90, chapters(12, 4, 6, 6));

        assertTrue(largeQuick.pagesLow() > smallQuick.pagesHigh(), largeQuick + " vs " + smallQuick);
        assertTrue(largeQuick.pagesHigh() < largeComprehensive.pagesLow() / 2,
                "quick stays selective: " + largeQuick + " vs " + largeComprehensive);
    }

    @Test
    void theBriefExampleLandsInTheExpectedBallpark() {
        // ~87 pages of material plus code and notes, comprehensive, a 14-chapter blueprint.
        ScopeEstimate e = materials(COMPREHENSIVE, 115, 70, chapters(14, 4, 5, 6));

        assertTrue(e.pagesLow() >= 100 && e.pagesHigh() <= 220, "87-page project, comprehensive: " + e);
        assertEquals(14, e.chaptersLow());
        assertEquals(14, e.chaptersHigh());
    }

    @Test
    void sourceVolumeHasDiminishingReturnsNotALinearFactor() {
        double first = ScopeEstimator.taperedVolume(50);
        double tenTimes = ScopeEstimator.taperedVolume(500);

        assertTrue(first > 40, "the first pages count almost one-for-one");
        assertTrue(tenTimes < first * 5, "10× the material is far less than 10× the book");
    }

    @Test
    void aHugeRepositoryIsCappedAtTheSafetyMaximumAndSaysSo() {
        ScopeEstimate e = materials(COMPREHENSIVE, 5000, 600, chapters(30, 8, 10, 12));

        assertTrue(e.pagesHigh() <= MAX);
        assertTrue(e.capped());
    }

    @Test
    void pastedSourceTextAddsToABriefOnlyBook() {
        ScopeEstimate without = brief(STANDARD);
        ScopeEstimate with = ScopeEstimator.estimate(ScopeEstimator.Signals.brief(STANDARD, 150, 0, 60 * PAGE), MAX);

        assertTrue(with.pagesHigh() > without.pagesHigh());
        assertEquals(ScopeEstimate.Basis.SOURCE_TEXT, with.basis());
    }

    @Test
    void aPromisedStructureGetsRoomForEveryUnit() {
        ScopeEstimate thirtyDays = ScopeEstimator.estimate(ScopeEstimator.Signals.brief(QUICK, 150, 30, 0), MAX);

        assertTrue(thirtyDays.pagesLow() >= 30, "30 days need real room even at quick: " + thirtyDays);
        assertTrue(thirtyDays.chaptersHigh() >= 31);
    }

    @Test
    void chapterSizeFollowsItsKnowledgeAndTheDepth() {
        ScopeEstimator.ChapterSignal thin = new ScopeEstimator.ChapterSignal(1, 1, 0, 0);
        ScopeEstimator.ChapterSignal rich = new ScopeEstimator.ChapterSignal(5, 8, 8, 3);

        assertTrue(ScopeEstimator.chapterPages(STANDARD, rich) > ScopeEstimator.chapterPages(STANDARD, thin));
        assertTrue(ScopeEstimator.chapterPages(COMPREHENSIVE, rich) > ScopeEstimator.chapterPages(QUICK, rich));
    }

    // ---- OpenAI assessment, bounded ---------------------------------------------

    @Test
    void theAiAssessmentRefinesTheEstimateWithinItsBounds() {
        ScopeEstimate heuristic = brief(STANDARD);
        ScopeEstimate refined = ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(40, 50), MAX);

        assertTrue(refined.aiAssessed());
        assertTrue(refined.pagesLow() >= 36 && refined.pagesHigh() <= 55, refined.toString());
    }

    @Test
    void aWildAiGuessCannotTurnABriefIntoA500PageBook() {
        ScopeEstimate heuristic = brief(STANDARD);
        ScopeEstimate refined = ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(450, 550), MAX);

        double heuristicMid = (heuristic.pagesLow() + heuristic.pagesHigh()) / 2.0;
        assertTrue(refined.pagesHigh() <= heuristicMid * ScopeEstimator.AI_MAX_FACTOR * 1.3, refined.toString());
        assertTrue(refined.pagesHigh() < 100);
    }

    @Test
    void anAiGuessCannotShrinkRichMaterialIntoASummary() {
        ScopeEstimate heuristic = materials(COMPREHENSIVE, 150, 90, chapters(12, 4, 6, 6));
        ScopeEstimate refined = ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(10, 15), MAX);

        double heuristicMid = (heuristic.pagesLow() + heuristic.pagesHigh()) / 2.0;
        assertTrue(refined.pagesLow() >= heuristicMid * ScopeEstimator.AI_MIN_FACTOR * 0.7, refined.toString());
    }

    @Test
    void eachDepthHasAHardCap() {
        ScopeEstimate quick = materials(QUICK, 3000, 400, chapters(30, 8, 10, 12));
        ScopeEstimate quickAi = ScopeEstimator.combine(quick, new AiScopeAssessment.Range(300, 400), MAX);

        assertTrue(quick.pagesHigh() <= ScopeEstimator.depthCap(QUICK));
        assertTrue(quickAi.pagesHigh() <= ScopeEstimator.depthCap(QUICK));
        assertTrue(ScopeEstimator.depthCap(QUICK) < ScopeEstimator.depthCap(STANDARD));
    }

    @Test
    void anInvalidAiRangeIsIgnored() {
        ScopeEstimate heuristic = brief(STANDARD);
        assertEquals(heuristic, ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(0, 0), MAX));
        assertEquals(heuristic, ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(50, 20), MAX));
        assertEquals(heuristic, ScopeEstimator.combine(heuristic, null, MAX));
    }
}
