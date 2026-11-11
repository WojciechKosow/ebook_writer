package com.ebookwriter.SaaS.service.ai;

/**
 * A model for one generation call, and the effort it should spend.
 *
 * @param model  the Claude model id
 * @param effort {@code low|medium|high|xhigh|max}, or null/blank for the model's default
 */
public record ModelChoice(String model, String effort) {

    /** The model at its default effort. */
    public static ModelChoice of(String model) {
        return new ModelChoice(model, null);
    }

    /** The effort, or null when the model's default applies. */
    public String effortOrNull() {
        return effort == null || effort.isBlank() ? null : effort.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
