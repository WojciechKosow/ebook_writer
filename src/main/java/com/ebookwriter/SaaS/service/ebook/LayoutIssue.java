package com.ebookwriter.SaaS.service.ebook;

/**
 * One finding of the layout check ({@link LayoutValidator}): what is wrong,
 * where (page and stable element id), the measured bounds against the allowed
 * ones, and how it can be repaired upstream.
 */
record LayoutIssue(Type type, Severity severity, int page, String element, String description,
                   String bounds, String allowed, Repair repair) {

    enum Type {
        CONTENT_OVERFLOW, TEXT_CLIPPING, TABLE_OVERFLOW, OUTSIDE_PAGE_BOUNDS, FOOTER_OVERLAP,
        ORPHAN_HEADING, SUSPICIOUS_EMPTY_PAGE, BROKEN_CONTINUATION, MALFORMED_TABLE, MISSING_REPEATED_HEADER
    }

    /**
     * ERROR: the PDF is not safe to deliver. WARNING: worth attention, not fatal.
     * (They map onto the book-level FATAL/WARNING of {@link EbookValidationService}.)
     */
    enum Severity { ERROR, WARNING }

    /** Upstream repairs ({@link LayoutRepair}); NONE when no deterministic fix exists. */
    enum Repair {
        /** Start the element's logical block on a new page. */
        BREAK_BEFORE,
        /** Let the element and its text wrap anywhere within the text column. */
        CONSTRAIN_WIDTH,
        /** Cap a replaced element (an image) to the page's content height. */
        CONSTRAIN_HEIGHT,
        NONE
    }

    boolean repairable() {
        return repair != Repair.NONE && element != null && !element.isBlank();
    }

    /** The key a repair is remembered by, so the same fix is never applied twice. */
    String repairKey() {
        return repair + "@" + element;
    }

    String diagnostic() {
        return String.format("%s %s on page %d — %s%n    element: %s%n    bounds: %s%n    allowed: %s%n    repair: %s",
                severity, type, page, description, element.isBlank() ? "-" : element, bounds, allowed, repair);
    }
}
