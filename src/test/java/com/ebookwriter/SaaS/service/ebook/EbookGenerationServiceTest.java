package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The money rule for reconciling a finished book: bill the real final page count
 * (1 credit = 1 final page), never above the reserved ceiling (which was sized so
 * the balance can't fall below -maxOverdraft) and never below one credit.
 */
class EbookGenerationServiceTest {

    @Test
    void chargesActualPagesWhenUnderTheCeiling() {
        // Target 15, ceiling 25 (target + overdraft): a 13-page book costs 13.
        assertEquals(13, EbookGenerationService.reconciledCharge(13, 25));
    }

    @Test
    void chargesTheRealLengthEvenWhenItExceedsTheTarget() {
        // Target 15 but the book naturally ran to 20 pages; the user pays for 20,
        // not the target — 1 credit = 1 final page.
        assertEquals(20, EbookGenerationService.reconciledCharge(20, 25));
    }

    @Test
    void neverChargesMoreThanTheReservedCeiling() {
        // A runaway is trimmed to the ceiling before it can breach the overdraft
        // floor, so it is billed at the ceiling (e.g. 25), never the raw overshoot.
        assertEquals(25, EbookGenerationService.reconciledCharge(30, 25));
    }

    @Test
    void chargesExactlyTheCeilingWhenPagesEqualIt() {
        assertEquals(25, EbookGenerationService.reconciledCharge(25, 25));
    }

    @Test
    void aProducedBookAlwaysCostsAtLeastOneCredit() {
        assertEquals(1, EbookGenerationService.reconciledCharge(0, 25));
    }
}
