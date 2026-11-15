package com.ebookwriter.SaaS.service.ai;

/**
 * The AI service refuses this server itself — an invalid, revoked or
 * under-privileged API key (HTTP 401/403). Nothing the book does can fix it, so
 * generation stops at once (no step-by-step degradation, no chapter retries) and
 * the author sees a plain message; the operator gets the details in the log.
 */
public class AiServiceUnavailableException extends ModelRequestRejectedException {

    /** What the author sees on the failed book (the API's own wording stays in the log). */
    public static final String USER_MESSAGE = "Scrivetta couldn't reach its AI writing service, so nothing was "
            + "written and your credits were returned in full. This is a problem on our side — please try again "
            + "a little later.";

    public AiServiceUnavailableException(int status, String detail, Throwable cause) {
        super(status, detail, cause);
    }

    @Override
    public String getMessage() {
        return USER_MESSAGE;
    }

    /** The API's explanation, for the log. */
    public String detail() {
        return super.getMessage();
    }
}
