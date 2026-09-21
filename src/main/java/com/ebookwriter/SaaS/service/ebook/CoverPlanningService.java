package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.dto.cover.CoverPlan;
import com.ebookwriter.SaaS.dto.image.AspectRatio;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.CoverPrompts;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Decides a book's cover: it asks the content model, as an art director, to
 * choose a {@link CoverLayout} and produce a <b>text-free</b> image prompt for
 * the visual only (title/subtitle stay real, editable text rendered by
 * Scrivetta). The model's JSON is parsed leniently; on any failure — or when the
 * model is unavailable — it falls back to a deterministic, topic-aware plan so a
 * cover can always be composed. Pure decision-maker: it never generates or stores
 * an image.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverPlanningService {

    private static final long PLAN_MAX_TOKENS = 1500L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;

    /**
     * Produce a cover plan for a book. Never throws: a model/parse failure yields
     * the deterministic fallback plan (an EDITORIAL cover with a topic-derived
     * prompt), so cover generation always has something to work with.
     */
    public CoverPlan plan(Ebook ebook, List<EbookChapter> chapters) {
        try {
            String system = CoverPrompts.system();
            String user = CoverPrompts.user(ebook, chapters == null ? List.of() : chapters);
            String raw = anthropicService.complete(system, user, PLAN_MAX_TOKENS);
            CoverPlan parsed = fromJson(raw, ebook);
            log.info("Planned cover for ebook {}: layout={}, visual='{}'",
                    ebook.getId(), parsed.layout(), parsed.visualConcept());
            return parsed;
        } catch (RuntimeException e) {
            log.warn("Cover planning failed for ebook {}; using deterministic fallback: {}",
                    ebook.getId(), e.getMessage());
            return fallback(ebook);
        }
    }

    /** Parse the model's JSON leniently into a validated {@link CoverPlan}. */
    static CoverPlan fromJson(String raw, Ebook ebook) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Model did not return a JSON object for the cover plan");
        }
        RawCover rc;
        try {
            rc = OBJECT_MAPPER.readValue(raw.substring(start, end + 1), RawCover.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable cover plan JSON: " + e.getMessage(), e);
        }

        CoverLayout layout = CoverLayout.fromString(rc.layout());
        String prompt = trimToNull(rc.imagePrompt());
        // A visual layout with no usable prompt is meaningless — fall back to the
        // topic-derived prompt rather than a blank image request.
        if (layout.requiresVisual() && prompt == null) {
            prompt = CoverPrompts.fallbackImagePrompt(ebook);
        }
        // A typographic cover ignores any prompt the model may have added.
        if (!layout.requiresVisual()) {
            prompt = null;
        }
        String concept = trimToNull(rc.visualConcept());
        AspectRatio ratio = AspectRatio.fromString(rc.aspectRatio());
        return new CoverPlan(layout, concept == null ? "(unspecified)" : concept, prompt, ratio);
    }

    /**
     * The deterministic plan: an EDITORIAL cover (a strong, generally-flattering
     * composition) with a topic-derived, text-free prompt and a portrait ratio.
     */
    static CoverPlan fallback(Ebook ebook) {
        return new CoverPlan(CoverLayout.EDITORIAL,
                "Topic-derived editorial visual (fallback)",
                CoverPrompts.fallbackImagePrompt(ebook),
                AspectRatio.PORTRAIT);
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawCover(String layout, String visualConcept, String imagePrompt, String aspectRatio) {
    }
}
