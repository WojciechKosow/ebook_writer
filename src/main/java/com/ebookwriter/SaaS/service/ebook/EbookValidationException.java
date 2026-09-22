package com.ebookwriter.SaaS.service.ebook;

/**
 * Thrown when a rendered book fails a <b>fatal</b> quality gate (see
 * {@link EbookValidationService}) — a genuinely broken book (no readable pages, no
 * rendered content) that must never be published as COMPLETED. The generation
 * pipeline catches it, moves the book to FAILED and refunds the up-front hold,
 * exactly as any other generation failure. Non-fatal problems are recorded as
 * warnings, not raised.
 */
public class EbookValidationException extends RuntimeException {

    public EbookValidationException(String message) {
        super(message);
    }
}
