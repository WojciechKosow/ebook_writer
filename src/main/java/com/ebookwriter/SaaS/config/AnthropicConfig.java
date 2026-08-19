package com.ebookwriter.SaaS.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
        String apiKey = (properties.getApiKey() != null && !properties.getApiKey().isBlank())
                ? properties.getApiKey()
                : PLACEHOLDER_KEY;

        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(properties.getTimeoutMinutes()))
                .maxRetries(properties.getMaxRetries())
                .build();
    }
}
