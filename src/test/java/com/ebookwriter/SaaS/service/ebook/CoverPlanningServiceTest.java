package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.dto.cover.CoverPlan;
import com.ebookwriter.SaaS.dto.image.AspectRatio;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.prompt.CoverPrompts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cover planner turns the art-director model's JSON into a validated plan and
 * always degrades to a usable, topic-derived fallback — so a cover can be
 * composed even when the model is unavailable or the image model is off.
 */
class CoverPlanningServiceTest {

    private static Ebook book() {
        return Ebook.builder()
                .title("The 7-Day Focus Reset")
                .subtitle("Stop checking your phone")
                .topic("How to regain focus and stop being distracted by your phone")
                .targetAudience("Busy professionals")
                .style("Practical, modern")
                .build();
    }

    @Test
    void parsesAWellFormedPlan() {
        String json = """
                Here is the cover:
                {
                  "layout": "IMAGE_LED",
                  "visualConcept": "A phone face-down on a calm wooden desk",
                  "imagePrompt": "A calm minimal workspace, phone placed away, soft daylight",
                  "aspectRatio": "2:3"
                }
                """;
        CoverPlan plan = CoverPlanningService.fromJson(json, book());
        assertEquals(CoverLayout.IMAGE_LED, plan.layout());
        assertEquals(AspectRatio.PORTRAIT, plan.aspectRatio());
        assertTrue(plan.imagePrompt().contains("calm"));
        assertTrue(plan.hasVisual());
    }

    @Test
    void typographicPlanDropsAnyImagePrompt() {
        String json = """
                {"layout":"TYPOGRAPHIC","visualConcept":"just type","imagePrompt":"ignored","aspectRatio":"2:3"}
                """;
        CoverPlan plan = CoverPlanningService.fromJson(json, book());
        assertEquals(CoverLayout.TYPOGRAPHIC, plan.layout());
        assertNull(plan.imagePrompt(), "a typographic cover carries no image prompt");
        assertFalse(plan.hasVisual());
    }

    @Test
    void visualLayoutWithoutPromptFallsBackToTopicPrompt() {
        String json = """
                {"layout":"EDITORIAL","visualConcept":"x","imagePrompt":"","aspectRatio":"2:3"}
                """;
        CoverPlan plan = CoverPlanningService.fromJson(json, book());
        assertEquals(CoverLayout.EDITORIAL, plan.layout());
        assertNotNull(plan.imagePrompt());
        assertTrue(plan.imagePrompt().toLowerCase().contains("focus")
                        || plan.imagePrompt().toLowerCase().contains("phone"),
                "the fallback prompt is derived from the book's own words");
    }

    @Test
    void unknownLayoutBecomesEditorial() {
        String json = """
                {"layout":"fancy","visualConcept":"x","imagePrompt":"a thing","aspectRatio":"2:3"}
                """;
        assertEquals(CoverLayout.EDITORIAL, CoverPlanningService.fromJson(json, book()).layout());
    }

    @Test
    void nonJsonThrowsSoTheServiceCanFallBack() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> CoverPlanningService.fromJson("no json here", book()));
    }

    @Test
    void deterministicFallbackIsUsableAndTextFree() {
        CoverPlan plan = CoverPlanningService.fallback(book());
        assertTrue(plan.hasVisual());
        assertEquals(CoverLayout.EDITORIAL, plan.layout());
        // The image prompt is topic-derived and the hard constraints ban text.
        assertTrue(plan.imagePrompt().toLowerCase().contains("focus")
                || plan.imagePrompt().toLowerCase().contains("phone"));
        assertTrue(CoverPrompts.IMAGE_CONSTRAINTS.toUpperCase().contains("NO TEXT"));
        assertTrue(CoverPrompts.IMAGE_CONSTRAINTS.toUpperCase().contains("NO LOGOS"));
    }
}
