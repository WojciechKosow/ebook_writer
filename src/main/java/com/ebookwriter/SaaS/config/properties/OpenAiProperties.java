package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI configuration — used by the AI image pipeline (planner + generator).
 * The provider (OpenAI Image API) is isolated behind
 * {@code com.ebookwriter.SaaS.service.image.OpenAiImageClient}; nothing else
 * talks to OpenAI directly.
 *
 * <p>Mirrors the Anthropic/R2 placeholder pattern: the app boots with the key
 * blank (only image generation is skipped, gracefully, until a key is set), so a
 * missing key never crashes startup. Set the {@code OPENAI_*} environment
 * variables to enable it.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    /** API key. Set OPENAI_API_KEY in the environment. */
    private String apiKey;

    /**
     * Base URL of the OpenAI (or compatible) REST API. Overridable so a proxy or
     * an Azure/compatible gateway can be pointed at without code changes.
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * Image-generation model. The default returns base64 PNG bytes directly,
     * which the client stores in R2. Configurable rather than hardcoded so the
     * model can be traded for cost/quality without touching code.
     */
    private String imageModel = "gpt-image-1";

    /** TCP connect timeout for image calls (ms). */
    private long connectTimeoutMs = 10_000;

    /** Response/read timeout for image calls (ms) — generation can take a while. */
    private long readTimeoutMs = 120_000;

    /** Extra retries on a failed image call before that single image is given up. */
    private int maxRetries = 2;

    /** Master switch for the AI image pipeline. Off = books are text-only. */
    private boolean enabled = true;

    /**
     * Hard ceiling on generated images per book. A cost/quality guard so a book
     * never generates an unbounded number of images. The planner is also told to
     * be selective; this enforces the limit regardless of what the model returns.
     */
    private int maxImagesPerBook = 6;

    /** Hard ceiling on generated images assigned to any single chapter. */
    private int maxImagesPerChapter = 2;

    /**
     * Rendering quality requested for the <b>cover</b> visual — the single most
     * visible asset, so it is generated at the model's best quality by default.
     * Sent as the API's {@code quality} parameter (gpt-image models accept
     * {@code low|medium|high|auto}); blank omits it (provider default). Inline
     * illustrations always use the provider default.
     */
    private String coverQuality = "high";

    /** True once an API key is present and the pipeline is enabled. */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
