package com.ebookwriter.SaaS.service.image;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.image.AspectRatio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Base64;

/**
 * The only place that talks to the OpenAI Image API. Isolating the provider here
 * keeps the planner, generator, controllers and ebook-generation code free of
 * any OpenAI specifics: they hand a prompt and an aspect ratio and get bytes
 * back, or an {@link ImageGenerationException} on failure.
 *
 * <p>Calls {@code POST /images/generations} and returns the first image as raw
 * PNG bytes (the configured model returns base64 image data). A small retry
 * (config: {@code openai.max-retries}) rides on top of the transport, matching
 * the Anthropic wrapper; deterministic client errors (a 4xx that is not a rate
 * limit) are not retried.
 */
@Slf4j
@Service
public class OpenAiImageClient {

    private static final String CONTENT_TYPE = "image/png";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final OpenAiProperties properties;

    public OpenAiImageClient(@Qualifier("openAiWebClient") WebClient webClient,
                             OpenAiProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /** Generated image bytes plus their MIME type, ready to store. */
    public record GeneratedImage(byte[] bytes, String contentType) {
    }

    /**
     * Generate one image from {@code prompt} at the given aspect ratio. Retries a
     * few times on transient failures; throws {@link ImageGenerationException}
     * once retries are exhausted or on a non-retryable provider error.
     */
    public GeneratedImage generate(String prompt, AspectRatio aspectRatio) {
        if (!properties.isConfigured()) {
            throw new ImageGenerationException("OpenAI is not configured (set OPENAI_API_KEY).");
        }
        String body = requestBody(prompt, aspectRatio);
        int attempts = Math.max(1, properties.getMaxRetries());
        ImageGenerationException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String response = webClient.post()
                        .uri("/images/generations")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                byte[] bytes = decodeFirstImage(response);
                return new GeneratedImage(bytes, CONTENT_TYPE);
            } catch (WebClientResponseException e) {
                last = new ImageGenerationException("OpenAI image API returned HTTP "
                        + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
                if (!isRetryable(e)) {
                    throw last; // deterministic client error — retrying won't help
                }
                log.warn("OpenAI image attempt {}/{} failed: HTTP {}",
                        attempt, attempts, e.getStatusCode().value());
            } catch (ImageGenerationException e) {
                last = e;
                log.warn("OpenAI image attempt {}/{} failed: {}", attempt, attempts, e.getMessage());
            } catch (RuntimeException e) {
                last = new ImageGenerationException("OpenAI image call failed: " + e.getMessage(), e);
                log.warn("OpenAI image attempt {}/{} failed: {}", attempt, attempts, e.getMessage());
            }
        }
        throw (last != null) ? last
                : new ImageGenerationException("OpenAI image generation failed");
    }

    private String requestBody(String prompt, AspectRatio aspectRatio) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("model", properties.getImageModel());
        node.put("prompt", prompt);
        node.put("size", aspectRatio.openAiSize());
        node.put("n", 1);
        return node.toString();
    }

    /** A 429 (rate limit) or any 5xx is worth retrying; other 4xx are not. */
    private static boolean isRetryable(WebClientResponseException e) {
        int code = e.getStatusCode().value();
        return code == 429 || code >= 500;
    }

    /**
     * Extract the first image's bytes from an OpenAI images response. The model
     * returns base64 under {@code data[0].b64_json}; a response carrying an
     * {@code error} object, or no base64 payload, is an
     * {@link ImageGenerationException}. Pure/static so response handling is
     * unit-testable without the network.
     */
    static byte[] decodeFirstImage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new ImageGenerationException("Empty response from OpenAI image API");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (Exception e) {
            throw new ImageGenerationException("Unparseable OpenAI image response: " + e.getMessage(), e);
        }
        if (root.hasNonNull("error")) {
            JsonNode message = root.get("error").get("message");
            throw new ImageGenerationException("OpenAI image error: "
                    + (message != null ? message.asText() : root.get("error").toString()));
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new ImageGenerationException("OpenAI image response contained no data");
        }
        JsonNode first = data.get(0);
        JsonNode b64 = first.get("b64_json");
        if (b64 == null || b64.asText().isBlank()) {
            throw new ImageGenerationException(
                    "OpenAI image response had no base64 data — configure a model that returns b64_json");
        }
        try {
            return Base64.getDecoder().decode(b64.asText());
        } catch (IllegalArgumentException e) {
            throw new ImageGenerationException("OpenAI returned invalid base64 image data", e);
        }
    }
}
