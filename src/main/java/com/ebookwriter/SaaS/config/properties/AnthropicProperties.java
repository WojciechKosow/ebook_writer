package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "anthropic")
public class AnthropicProperties {

    /** API key. Set ANTHROPIC_API_KEY in the environment. */
    private String apiKey;

    /**
     * Model id used for all generation calls. Must be a Claude 4.6+ model
     * (adaptive thinking is used). Override with ANTHROPIC_MODEL if needed.
     */
    private String model = "claude-opus-5";

    /** Client request timeout in minutes — generation calls can be long. */
    private int timeoutMinutes = 15;

    /** How many times to retry a failed generation call before giving up. */
    private int maxRetries = 2;

    /**
     * Whether to run the Step 3 editorial pass. It roughly doubles the cost of
     * a book (a second full pass over every chapter), so it can be turned off
     * for cheap validation runs. Default on.
     */
    private boolean editingEnabled = true;

    /**
     * Model to use for the editorial pass. Blank = same as {@link #model}. Set
     * this to a cheaper model (e.g. claude-sonnet-5) to keep the consistency
     * pass at a fraction of the cost.
     */
    private String editingModel;

    /** Resolve the model used for editing (falls back to the main model). */
    public String resolveEditingModel() {
        return (editingModel == null || editingModel.isBlank()) ? model : editingModel.trim();
    }
}
