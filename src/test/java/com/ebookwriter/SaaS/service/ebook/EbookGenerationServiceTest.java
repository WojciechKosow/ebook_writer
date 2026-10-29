package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The money rules for reconciling a finished book: bill the real final page
 * count (never above the reserved page ceiling, never below one page), and never
 * charge more credits than were held (the hold was sized so the balance can't
 * fall below the bounded overdraft) or less than one credit.
 */
class EbookGenerationServiceTest {

    @Test
    void billsActualPagesWhenUnderTheCeiling() {
        // Target 15, ceiling 25 (target + overdraft): a 13-page book is billed 13 pages.
        assertEquals(13, EbookGenerationService.reconciledPages(13, 25));
    }

    @Test
    void billsTheRealLengthEvenWhenItExceedsTheTarget() {
        assertEquals(20, EbookGenerationService.reconciledPages(20, 25));
    }

    @Test
    void neverBillsMorePagesThanTheReservedCeiling() {
        assertEquals(25, EbookGenerationService.reconciledPages(30, 25));
    }

    @Test
    void aProducedBookIsAlwaysBilledAtLeastOnePage() {
        assertEquals(1, EbookGenerationService.reconciledPages(0, 25));
    }

    @Test
    void chargesTheCostWhenItIsWithinTheHold() {
        assertEquals(1500, EbookGenerationService.reconciledCharge(1500, 2000));
    }

    @Test
    void neverChargesMoreThanWasHeld() {
        // e.g. images reserved but the cost still came in above the hold.
        assertEquals(2000, EbookGenerationService.reconciledCharge(2400, 2000));
    }

    @Test
    void aProducedBookAlwaysCostsAtLeastOneCredit() {
        assertEquals(1, EbookGenerationService.reconciledCharge(0, 2000));
    }
}
