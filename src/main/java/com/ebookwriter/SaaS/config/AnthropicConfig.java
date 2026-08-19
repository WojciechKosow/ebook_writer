package com.ebookwriter.SaaS.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class AnthropicConfig {

    /**
     * When no API key is configured we still build the client with a
     * placeholder so the application boots (auth and everything else work);
     * only actual ebook generation fails — surfaced as a FAILED ebook rather
     * than a startup crash.
     */
    private static final String PLACEHOLDER_KEY = "not-configured-set-ANTHROPIC_API_KEY";

    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        String configured = properties.getApiKey() == null ? "" : properties.getApiKey().trim();
        boolean present = !configured.isBlank();
        String apiKey = present ? configured : PLACEHOLDER_KEY;

        // Masked diagnostic — never logs the key itself, only whether one was
        // found and its length, so a 401 can be traced to config vs. a bad key.
        if (present) {
            log.info("Anthropic API key detected (length={}, model={}).",
                    configured.length(), properties.getModel());
        } else {
            log.warn("No Anthropic API key configured (anthropic.api-key / ANTHROPIC_API_KEY is empty). "
                    + "Ebook generation will fail with 401 until one is set.");
        }

        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(properties.getTimeoutMinutes()))
                .maxRetries(properties.getMaxRetries())
                .build();
    }
}
