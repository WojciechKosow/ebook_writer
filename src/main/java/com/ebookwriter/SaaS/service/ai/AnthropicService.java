package com.ebookwriter.SaaS.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Thin wrapper over the Anthropic SDK for the ebook pipeline. All generation
 * steps go through {@link #complete} so retry, model selection, and thinking
 * config live in one place. Adaptive thinking is always on, so the configured
 * model must be a Claude 4.6+ model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnthropicService {

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties properties;

    /**
     * Run a single completion. The SDK already retries transient HTTP failures
     * (429/5xx); this adds a couple of extra attempts so an occasional empty or
     * malformed response doesn't fail a whole book.
     */
    public String complete(String systemPrompt, String userPrompt, long maxTokens) {
        return complete(systemPrompt, userPrompt, maxTokens, properties.getModel());
    }

    /** As {@link #complete(String, String, long)} but with an explicit model. */
    public String complete(String systemPrompt, String userPrompt, long maxTokens, String model) {
        return completeDetailed(systemPrompt, userPrompt, maxTokens, model).text();
    }

    /**
     * A completion's text plus whether the model was cut off by the output-token
     * limit. Long-form steps use this to detect a chapter that stopped mid-thought
     * and repair it to a clean boundary instead of shipping half a sentence.
     */
    public record Completion(String text, boolean truncated) {
    }

    /** As {@link #complete(String, String, long)}, reporting truncation. */
    public Completion completeDetailed(String systemPrompt, String userPrompt, long maxTokens) {
        return completeDetailed(systemPrompt, userPrompt, maxTokens, properties.getModel());
    }

    /** As {@link #complete(String, String, long, String)}, reporting truncation. */
    public Completion completeDetailed(String systemPrompt, String userPrompt, long maxTokens, String model) {

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .addUserMessage(userPrompt)
                .build();

        RuntimeException last = null;

        for (int attempt = 1; attempt <= Math.max(1, properties.getMaxRetries()); attempt++) {
            try {
                Message response = anthropicClient.messages().create(params);

                String text = response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(block -> block.text())
                        .collect(Collectors.joining("\n"))
                        .trim();

                if (text.isEmpty()) {
                    throw new IllegalStateException("Model returned no text content");
                }
                boolean truncated = response.stopReason()
                        .map(r -> r.equals(StopReason.MAX_TOKENS))
                        .orElse(false);
                if (truncated) {
                    log.warn("Anthropic completion hit the output-token limit ({} tokens)", maxTokens);
                }
                return new Completion(text, truncated);

            } catch (RuntimeException e) {
                last = e;
                log.warn("Anthropic completion attempt {}/{} failed: {}",
                        attempt, properties.getMaxRetries(), e.getMessage());
            }
        }

        throw new RuntimeException("Anthropic completion failed after "
                + properties.getMaxRetries() + " attempts", last);
    }
}
