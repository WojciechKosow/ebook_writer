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
}
