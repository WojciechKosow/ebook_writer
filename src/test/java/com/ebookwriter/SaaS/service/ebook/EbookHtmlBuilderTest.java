package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The opener title de-duplicates the unit label: an opener that already shows
 * "DAY 01" shouldn't also repeat "Day 1 —" in the title.
 */
class EbookHtmlBuilderTest {

    @Test
    void stripsUnitPrefixOnlyForUnitOpeners() {
        assertEquals("Remove the Biggest Distractions",
                EbookHtmlBuilder.displayTitle("Day 1 — Remove the Biggest Distractions", true));
        assertEquals("Build a Focus Block",
                EbookHtmlBuilder.displayTitle("Day 2: Build a Focus Block", true));
        // Non-unit chapters are never stripped.
        assertEquals("Day 1 — Remove the Biggest Distractions",
                EbookHtmlBuilder.displayTitle("Day 1 — Remove the Biggest Distractions", false));
    }

    @Test
    void keepsOriginalWhenStrippingWouldEmptyIt() {
        assertEquals("Day 1", EbookHtmlBuilder.displayTitle("Day 1", true));
    }
}
