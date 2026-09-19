package com.ebookwriter.SaaS.service.image;

/**
 * Thrown when a single image could not be generated (provider error, timeout,
 * content policy refusal, or an unusable response). Callers treat it as a
 * per-image failure: the offending image is skipped and the book continues.
 */
public class ImageGenerationException extends RuntimeException {

    public ImageGenerationException(String message) {
        super(message);
    }

    public ImageGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
