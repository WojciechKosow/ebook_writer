package com.ebookwriter.SaaS.exceptions;

/** Thrown when a user tries to spend more credits than they have. */
public class InsufficientCreditsException extends RuntimeException {

    private final int required;
    private final int available;

    public InsufficientCreditsException(int required, int available) {
        this("Insufficient credits: need " + required + ", have " + available, required, available);
    }

    /** With a user-facing explanation (e.g. why this book needs that many credits). */
    public InsufficientCreditsException(String message, int required, int available) {
        super(message);
        this.required = required;
        this.available = available;
    }

    public int getRequired() {
        return required;
    }

    public int getAvailable() {
        return available;
    }
}
