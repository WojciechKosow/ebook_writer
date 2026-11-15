package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.entity.WritingQuality;
import com.ebookwriter.SaaS.service.ai.AiServiceUnavailableException;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.ai.ModelRequestRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Verifies at startup that Anthropic accepts the configured API key and knows
 * the models the pipeline uses — a free models lookup, no tokens spent. A key
 * that is present but invalid or revoked otherwise only shows up when the first
 * author's book fails. Never fails the boot (like {@link R2StartupCheck}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicStartupCheck implements ApplicationRunner {

    private final AnthropicService anthropicService;
    private final AnthropicProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return; // AnthropicConfig already warned that no key is set
        }
        Set<String> models = new LinkedHashSet<>();
        models.add(properties.getModel());
        models.add(properties.writerFor(WritingQuality.BASIC).model());
        models.add(properties.resolveEditingModel());
        for (String model : models) {
            try {
                anthropicService.verifyAccess(model);
            } catch (AiServiceUnavailableException e) {
                log.error("=================================================================");
                log.error("[Anthropic] The API rejects the configured key (HTTP {}): {}", e.status(), e.detail());
                log.error("[Anthropic] Every ebook generation will fail until ANTHROPIC_API_KEY is replaced "
                        + "with a valid key (Claude Console → API keys) and the app is restarted.");
                log.error("=================================================================");
                return;
            } catch (ModelRequestRejectedException e) {
                log.error("[Anthropic] {}", e.getMessage());
                continue;
            }
            log.info("[Anthropic] Key accepted; model {} available.", model);
        }
    }
}
