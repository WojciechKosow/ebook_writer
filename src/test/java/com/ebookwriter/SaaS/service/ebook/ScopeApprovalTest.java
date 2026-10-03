package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a generation stops to ask the user, and when small drift just carries on. */
class ScopeApprovalTest {

    private static Ebook book(Integer approved, int pageBudget, boolean fit) {
        return Ebook.builder().topic("t").approvedPages(approved).pageBudget(pageBudget).fitToBudget(fit).build();
    }

    @Test
    void aPlanSlightlyOverTheAgreedLengthCarriesOn() {
        assertFalse(ScopeApproval.planNeedsApproval(44, 50, book(41, 120, false)), "within 10%");
    }

    @Test
    void aPlanClearlyLongerThanAgreedAsks() {
        assertTrue(ScopeApproval.planNeedsApproval(60, 70, book(41, 400, false)));
    }

    @Test
    void aPlanTheHoldCannotCoverAsksEvenWithinTheAgreedLength() {
        assertTrue(ScopeApproval.planNeedsApproval(40, 60, book(41, 51, false)));
    }

    @Test
    void afterTheUserChoseToFitNothingAsksAgain() {
        assertFalse(ScopeApproval.planNeedsApproval(90, 120, book(41, 51, true)));
        assertFalse(ScopeApproval.writingNeedsApproval(90, book(41, 51, true)));
    }

    @Test
    void writingAsksOnlyWhenTheProjectionIsClearlyPastTheAgreedLength() {
        assertFalse(ScopeApproval.writingNeedsApproval(46, book(41, 200, false)), "within 15%");
        assertTrue(ScopeApproval.writingNeedsApproval(50, book(41, 200, false)));
    }

    @Test
    void projectionCountsWrittenAndPlannedWords() {
        assertEquals(EbookHtmlBuilder.FRONT_MATTER_PAGES + 10,
                ScopeApproval.projectedPages(1000, 1000));
    }

    @Test
    void extraHoldIsOnlyWhatIsMissing() {
        assertEquals(19, ScopeApproval.extraHold(70, 51));
        assertEquals(0, ScopeApproval.extraHold(40, 51));
        assertEquals(0, ScopeApproval.extraHold(null, 51));
    }

    @Test
    void holdForIsTheInverseOfTheWritingCapacity() {
        int hold = WritingBudget.holdFor(12_000, 8, 3);
        assertTrue(WritingBudget.capacityWords(hold, 8, 3) >= 12_000);
        assertTrue(WritingBudget.capacityWords(hold - 1, 8, 3) < 12_000);
    }
}
