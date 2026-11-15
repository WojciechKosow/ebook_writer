package com.ebookwriter.SaaS.service.ai;

/**
 * The API rejected the request itself (HTTP 400, 401, 403, 404, 413 or 422):
 * the same request fails the same way every time, so it is not retried. The
 * message carries the status and the API's own explanation so the cause shows in
 * the log and on the failed chapter, not just "failed after 2 attempts".
 */
public class ModelRequestRejectedException extends RuntimeException {

    private final int status;

    public ModelRequestRejectedException(int status, String detail, Throwable cause) {
        super("Anthropic rejected the request (HTTP " + status + "): " + detail, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
