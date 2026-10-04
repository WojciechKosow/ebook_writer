package com.ebookwriter.SaaS.service.ebook;

/**
 * The rendered layout still had errors after the bounded repair rounds: the
 * PDF is not safe to deliver and was not written. A kind of
 * {@link EbookValidationException}, so the generation pipeline fails the book
 * (and refunds the hold) exactly as for any other failed final validation.
 */
public class LayoutValidationException extends EbookValidationException {

    private final transient LayoutValidationResult result;
    private final int attempts;
    private final String diagnostics;

    LayoutValidationException(LayoutValidationResult result, int attempts, String diagnostics) {
        super("PDF layout validation failed after " + attempts + " layout(s): "
                + String.join("; ", result.errors().stream()
                .map(i -> i.type() + " on page " + i.page()).distinct().limit(5).toList()));
        this.result = result;
        this.attempts = attempts;
        this.diagnostics = diagnostics;
    }

    LayoutValidationResult result() {
        return result;
    }

    int attempts() {
        return attempts;
    }

    /** The full per-attempt report (issues, bounds, repairs tried), for logs and tests. */
    public String diagnostics() {
        return diagnostics;
    }
}
