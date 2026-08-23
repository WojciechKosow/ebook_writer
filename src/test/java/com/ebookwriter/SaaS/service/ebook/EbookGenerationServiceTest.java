package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The money rule for reconciling a finished book: bill the real page count, but
 * never above the reserved budget and never below one credit.
 */
class EbookGenerationServiceTest {

    @Test
    void chargesActualPagesWhenUnderBudget() {
        // Reserved 60 (e.g. asked 50, +20% headroom), book came in at 42 pages.
        assertEquals(42, EbookGenerationService.reconciledCharge(42, 60));
    }

    @Test
    void neverChargesMoreThanTheReservedBudget() {
        // Even if the model overshot the clamp, the user only authorised 60.
        assertEquals(60, EbookGenerationService.reconciledCharge(75, 60));
    }

    @Test
    void chargesExactlyBudgetWhenPagesEqualBudget() {
        assertEquals(60, EbookGenerationService.reconciledCharge(60, 60));
    }

    @Test
    void aProducedBookAlwaysCostsAtLeastOneCredit() {
        assertEquals(1, EbookGenerationService.reconciledCharge(0, 60));
    }
}
