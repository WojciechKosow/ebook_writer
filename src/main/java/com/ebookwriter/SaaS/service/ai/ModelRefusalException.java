package com.ebookwriter.SaaS.service.ai;

/**
 * The model declined the request ({@code stop_reason: "refusal"}). Sending the
 * same request again gets the same answer, so it is not retried as is: the
 * caller changes the request (leaves out what most likely triggered it, or asks
 * another model) or fails with a message the author can act on.
 */
public class ModelRefusalException extends RuntimeException {

    private final String model;

    public ModelRefusalException(String model) {
        this(model, "the AI model (" + model + ") declined the request");
    }

    public ModelRefusalException(String model, String message) {
        super(message);
        this.model = model;
    }

    public String model() {
        return model;
    }
}
