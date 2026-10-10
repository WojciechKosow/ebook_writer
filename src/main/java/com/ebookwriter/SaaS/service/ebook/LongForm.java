package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ai.AnthropicService;
import lombok.extern.slf4j.Slf4j;

/**
 * Long-form completions that never come back silently cut off. When the model
 * stops at its output limit, the answer is continued from where it stopped
 * ({@link AnthropicService#continueFrom}) and the parts are joined, up to
 * {@link #MAX_CONTINUATIONS} times. The result reports whether it is still
 * truncated after that, so the caller can regenerate or fail loudly — it never
 * has to guess.
 */
@Slf4j
final class LongForm {

    static final int MAX_CONTINUATIONS = 3;
    /** The shortest repeated stretch treated as the model restating its last words. */
    static final int MIN_OVERLAP = 20;
    static final int MAX_OVERLAP = 400;

    private LongForm() {
    }

    /**
     * Complete, continuing past the output limit.
     *
     * @param model the model, or null for the default
     */
    static AnthropicService.Completion complete(AnthropicService ai, String system, String user, long maxTokens,
                                                String model) {
        AnthropicService.Completion c = model == null
                ? ai.completeDetailed(system, user, maxTokens)
                : ai.completeDetailed(system, user, maxTokens, model);
        String text = c.text();
        boolean truncated = c.truncated();
        for (int round = 1; truncated && round <= MAX_CONTINUATIONS; round++) {
            log.info("Output limit reached; continuing from where it stopped (continuation {}/{})",
                    round, MAX_CONTINUATIONS);
            AnthropicService.Completion next = model == null
                    ? ai.continueFrom(system, user, text, maxTokens)
                    : ai.continueFrom(system, user, text, maxTokens, model);
            text = join(text, next.text());
            truncated = next.truncated();
        }
        return new AnthropicService.Completion(text, truncated);
    }

    /**
     * {@code partial} followed by {@code continuation}, dropping any stretch the
     * continuation repeats from the end of the partial text.
     */
    static String join(String partial, String continuation) {
        String head = partial;
        String c = continuation == null ? "" : continuation;
        for (String candidate : new String[]{c, c.stripLeading()}) {
            int max = Math.min(MAX_OVERLAP, Math.min(candidate.length(), head.length()));
            for (int k = max; k >= MIN_OVERLAP; k--) {
                if (head.endsWith(candidate.substring(0, k))) {
                    return head + candidate.substring(k);
                }
            }
        }
        return head + c;
    }
}
