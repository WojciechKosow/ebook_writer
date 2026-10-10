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
        return send(params, maxTokens, true);
    }

    /**
     * What the model is asked when its previous answer stopped at the output
     * limit. The partial answer goes back as the assistant's turn, followed by
     * this request (an assistant turn may not end the conversation).
     */
    public static final String CONTINUE_PROMPT = """
            Your previous answer was cut off by the output limit where it ends above.
            Continue EXACTLY from where it stopped: begin with the very next characters
            (mid-word or mid-line if that is where it stopped), do not repeat anything
            already written, add no preface or comment, and keep the same format —
            including any delimiters and sections that still have to follow.""";

    /**
     * Continue a completion that stopped at its output limit: the original
     * request, the text produced so far as the assistant's turn, and
     * {@link #CONTINUE_PROMPT}. Returns only the <b>new</b> text (the caller
     * joins it), and whether this part was cut off too.
     */
    public Completion continueFrom(String systemPrompt, String userPrompt, String partial, long maxTokens,
                                   String model) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .addUserMessage(userPrompt)
                .addAssistantMessage(partial)
                .addUserMessage(CONTINUE_PROMPT)
                .build();
        // Leading whitespace is part of the continuation (a paragraph break, the
        // space between two words), so only the end is trimmed.
        return send(params, maxTokens, false);
    }

    /** As {@link #continueFrom(String, String, String, long, String)} on the default model. */
    public Completion continueFrom(String systemPrompt, String userPrompt, String partial, long maxTokens) {
        return continueFrom(systemPrompt, userPrompt, partial, maxTokens, properties.getModel());
    }

    private Completion send(MessageCreateParams params, long maxTokens, boolean trimStart) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= Math.max(1, properties.getMaxRetries()); attempt++) {
            try {
                Message response = anthropicClient.messages().create(params);

                String text = response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(block -> block.text())
                        .collect(Collectors.joining("\n"));
                text = trimStart ? text.strip() : text.stripTrailing();

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
