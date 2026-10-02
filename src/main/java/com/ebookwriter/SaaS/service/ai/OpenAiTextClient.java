package com.ebookwriter.SaaS.service.ai;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The only place that talks to the OpenAI <b>text</b> API (chat completions).
 * Used by knowledge ingestion — the cheap processing layer that reads the
 * author's materials. Claude ({@link AnthropicService}) stays the book-writing
 * engine; nothing here ever calls it.
 *
 * <p>One operation: send a system + user prompt and get a JSON object back
 * ({@code response_format: json_object}), together with the token usage so the
 * caller can account for cost. The model comes from
 * {@code openai.knowledge-model}; a small retry rides on transient failures (429
 * / 5xx / network), deterministic client errors are not retried.
 */
@Slf4j
@Service
public class OpenAiTextClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final OpenAiProperties properties;

    /** Base delay between retries (doubles each attempt); lowered in tests. */
    private long backoffBaseMs = 1_000L;

    public OpenAiTextClient(@Qualifier("openAiTextWebClient") WebClient webClient,
                            OpenAiProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /**
     * A completed JSON call.
     *
     * @param json         the parsed JSON object the model returned
     * @param model        the model that answered (as reported by the API)
     * @param inputTokens  prompt tokens billed
     * @param outputTokens completion tokens billed (including reasoning tokens)
     * @param attempts     HTTP attempts made (retries included)
     */
    public record JsonCompletion(JsonNode json, String model, long inputTokens, long outputTokens, int attempts) {
    }

    public boolean isConfigured() {
        return properties.isKnowledgeConfigured();
    }

    public String model() {
        return properties.getKnowledgeModel();
    }

    /** Ask the knowledge model for a JSON object. Throws {@link OpenAiTextException} on failure. */
    public JsonCompletion completeJson(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new OpenAiTextException("OpenAI is not configured (set OPENAI_API_KEY).", false);
        }
        String body = requestBody(systemPrompt, userPrompt);
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        OpenAiTextException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String response = webClient.post()
                        .uri("/chat/completions")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                JsonCompletion parsed = parseResponse(response);
                return new JsonCompletion(parsed.json(), parsed.model(), parsed.inputTokens(),
                        parsed.outputTokens(), attempt);
            } catch (WebClientResponseException e) {
                int code = e.getStatusCode().value();
                last = new OpenAiTextException("OpenAI API returned HTTP " + code + ": "
                        + abbreviate(e.getResponseBodyAsString()), code == 429 || code >= 500, e);
            } catch (OpenAiTextException e) {
                last = e;
            } catch (RuntimeException e) {
                // Network errors / timeouts.
                last = new OpenAiTextException("OpenAI call failed: " + e.getMessage(), true, e);
            }
            if (!last.isRetryable()) throw last;
            log.warn("OpenAI knowledge call attempt {}/{} failed: {}", attempt, attempts, last.getMessage());
            if (attempt < attempts) backoff(attempt);
        }
        throw last;
    }

    String requestBody(String systemPrompt, String userPrompt) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("model", properties.getKnowledgeModel());
        ArrayNode messages = node.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        node.putObject("response_format").put("type", "json_object");
        node.put("max_completion_tokens", properties.getKnowledgeMaxOutputTokens());
        String effort = properties.getKnowledgeReasoningEffort();
        if (effort != null && !effort.isBlank()) {
            node.put("reasoning_effort", effort.strip());
        }
        return node.toString();
    }

    /**
     * Parse a chat-completions response into the model's JSON object plus usage.
     * Static and network-free so the response handling is unit-testable.
     */
    static JsonCompletion parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new OpenAiTextException("Empty response from OpenAI", true);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (Exception e) {
            throw new OpenAiTextException("Unparseable OpenAI response", true, e);
        }
        if (root.hasNonNull("error")) {
            JsonNode message = root.get("error").get("message");
            throw new OpenAiTextException("OpenAI error: "
                    + (message != null ? message.asText() : root.get("error").toString()), false);
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new OpenAiTextException("OpenAI response contained no choices", true);
        }
        String finish = choice.path("finish_reason").asText("");
        JsonNode message = choice.path("message");
        if (message.hasNonNull("refusal") && !message.get("refusal").asText().isBlank()) {
            throw new OpenAiTextException("OpenAI refused: " + message.get("refusal").asText(), false);
        }
        if ("length".equals(finish)) {
            // The JSON would be cut off; retrying the same request gives the same result.
            throw new OpenAiTextException("OpenAI output hit the token limit (raise openai.knowledge-max-output-tokens"
                    + " or lower knowledge.max-chars-per-chunk)", false);
        }
        String content = message.path("content").asText("");
        JsonNode json;
        try {
            json = MAPPER.readTree(stripFences(content));
        } catch (Exception e) {
            throw new OpenAiTextException("OpenAI returned invalid JSON", true, e);
        }
        if (json == null || !json.isObject()) {
            throw new OpenAiTextException("OpenAI returned JSON that is not an object", true);
        }
        JsonNode usage = root.path("usage");
        return new JsonCompletion(json, root.path("model").asText(null),
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0), 1);
    }

    private static String stripFences(String content) {
        String s = content.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) s = s.substring(firstNewline + 1, lastFence);
        }
        return s;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(8_000L, backoffBaseMs * (1L << (attempt - 1))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiTextException("Interrupted while retrying OpenAI", false, e);
        }
    }
}
