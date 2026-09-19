package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.image.ImagePlan;
import com.ebookwriter.SaaS.dto.image.ImageType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.ebook.ImagePlanningService.RawImage;
import com.ebookwriter.SaaS.service.ebook.ImagePlanningService.RawPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The planner must turn the model's JSON into a validated, capped
 * {@code List<ImagePlan>}: drop malformed entries, never crash on bad output,
 * and respect the per-chapter / per-book limits.
 */
class ImagePlanningServiceTest {

    private final AnthropicService anthropic = mock(AnthropicService.class);
    private final EbookRepository ebookRepository = mock(EbookRepository.class);
    private final EbookChapterRepository chapterRepository = mock(EbookChapterRepository.class);

    // ---- validation (pure) --------------------------------------------------

    @Test
    void validatesCapsAndAssignsSequentialIds() {
        RawPlan raw = new RawPlan(List.of(
                new RawImage(1, "Intro", "DIAGRAM", "explain", "a diagram", "draw a diagram", "3:2", 2),
                new RawImage(2, null, "chart", "show data", "a chart", "draw a chart", "16:9", 1),
                new RawImage(3, null, "photo", null, null, "a photo", "2:3", 3)));

        List<ImagePlan> plans =
                ImagePlanningService.toValidatedPlans(raw, Set.of(1, 2, 3), 6, 2);

        assertEquals(3, plans.size());
        // Ranked by priority (1 first), ids assigned in kept order.
        assertEquals(2, plans.get(0).chapterNumber()); // priority 1
        assertEquals("img_1", plans.get(0).id());
        assertEquals(ImageType.CHART, plans.get(0).type());
        assertEquals("img_2", plans.get(1).id());
        assertEquals("img_3", plans.get(2).id());
    }

    @Test
    void dropsEntriesWithoutPromptOrRealChapter() {
        RawPlan raw = new RawPlan(java.util.Arrays.asList(
                new RawImage(1, null, "DIAGRAM", "p", "d", "  ", "3:2", 1),   // blank prompt -> dropped
                new RawImage(9, null, "DIAGRAM", "p", "d", "prompt", "3:2", 1), // unknown chapter -> dropped
                new RawImage(null, null, "DIAGRAM", "p", "d", "prompt", "3:2", 1), // no chapter -> dropped
                null,                                                            // null entry -> dropped
                new RawImage(2, null, "DIAGRAM", "p", "d", "good prompt", "3:2", 1)));

        List<ImagePlan> plans =
                ImagePlanningService.toValidatedPlans(raw, Set.of(1, 2), 6, 2);

        assertEquals(1, plans.size());
        assertEquals(2, plans.get(0).chapterNumber());
        assertEquals("good prompt", plans.get(0).generationPrompt());
    }

    @Test
    void enforcesPerChapterCap() {
        RawPlan raw = new RawPlan(List.of(
                new RawImage(1, null, "DIAGRAM", "p", "d", "one", "3:2", 1),
                new RawImage(1, null, "DIAGRAM", "p", "d", "two", "3:2", 2),
                new RawImage(1, null, "DIAGRAM", "p", "d", "three", "3:2", 3)));

        List<ImagePlan> plans =
                ImagePlanningService.toValidatedPlans(raw, Set.of(1), 6, 2);

        assertEquals(2, plans.size(), "per-chapter cap of 2 must bind");
        assertTrue(plans.stream().allMatch(p -> p.chapterNumber() == 1));
    }

    @Test
    void enforcesPerBookCapKeepingHighestPriority() {
        RawPlan raw = new RawPlan(List.of(
                new RawImage(1, null, "DIAGRAM", "p", "d", "low", "3:2", 5),
                new RawImage(2, null, "DIAGRAM", "p", "d", "high", "3:2", 1),
                new RawImage(3, null, "DIAGRAM", "p", "d", "mid", "3:2", 3)));

        List<ImagePlan> plans =
                ImagePlanningService.toValidatedPlans(raw, Set.of(1, 2, 3), 1, 2);

        assertEquals(1, plans.size());
        assertEquals("high", plans.get(0).generationPrompt());
    }

    @Test
    void emptyOrNullPlanYieldsNoImages() {
        assertTrue(ImagePlanningService.toValidatedPlans(null, Set.of(1), 6, 2).isEmpty());
        assertTrue(ImagePlanningService.toValidatedPlans(new RawPlan(null), Set.of(1), 6, 2).isEmpty());
        assertTrue(ImagePlanningService.toValidatedPlans(new RawPlan(List.of()), Set.of(), 6, 2).isEmpty());
    }

    // ---- end-to-end plan() with a mocked model ------------------------------

    @Test
    void planParsesModelJsonAcrossMultipleChapters() {
        ImagePlanningService service = service(props(true, 6, 2));
        UUID id = ebookWith2Chapters();

        when(anthropic.complete(any(), any(), anyLong())).thenReturn("""
                Here is the plan:
                {
                  "images": [
                    {"chapterNumber": 1, "anchorHeading": "Basics", "type": "DIAGRAM",
                     "purpose": "clarify", "description": "a labelled diagram",
                     "generationPrompt": "draw a clean diagram", "aspectRatio": "3:2", "priority": 1},
                    {"chapterNumber": 2, "anchorHeading": null, "type": "CHART",
                     "purpose": "show growth", "description": "a growth chart",
                     "generationPrompt": "draw a growth chart", "aspectRatio": "16:9", "priority": 2}
                  ]
                }
                """);

        List<ImagePlan> plans = service.plan(id);

        assertEquals(2, plans.size());
        assertEquals(1, plans.get(0).chapterNumber());
        assertEquals(ImageType.DIAGRAM, plans.get(0).type());
        assertEquals(2, plans.get(1).chapterNumber());
    }

    @Test
    void planReturnsEmptyOnMalformedModelOutput() {
        ImagePlanningService service = service(props(true, 6, 2));
        UUID id = ebookWith2Chapters();
        when(anthropic.complete(any(), any(), anyLong()))
                .thenReturn("Sorry, I can't produce JSON right now.");

        // Best-effort: a malformed response must not throw, just yield no images.
        assertTrue(service.plan(id).isEmpty());
    }

    @Test
    void planShortCircuitsWhenDisabled() {
        ImagePlanningService service = service(props(false, 6, 2));
        // No stubbing of repositories needed: disabled returns before touching them.
        assertTrue(service.plan(UUID.randomUUID()).isEmpty());
    }

    // ---- helpers ------------------------------------------------------------

    private ImagePlanningService service(OpenAiProperties props) {
        return new ImagePlanningService(anthropic, ebookRepository, chapterRepository, props);
    }

    private static OpenAiProperties props(boolean enabled, int perBook, int perChapter) {
        OpenAiProperties p = new OpenAiProperties();
        p.setEnabled(enabled);
        p.setMaxImagesPerBook(perBook);
        p.setMaxImagesPerChapter(perChapter);
        return p;
    }

    private UUID ebookWith2Chapters() {
        UUID id = UUID.randomUUID();
        Ebook ebook = Ebook.builder().id(id).topic("Finance").language("English").build();
        when(ebookRepository.findById(id)).thenReturn(java.util.Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(id)).thenReturn(List.of(
                EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(1)
                        .title("Basics").content("## Basics\n\nSome intro content about money.").build(),
                EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(2)
                        .title("Growth").content("Compound interest makes money grow over time.").build()));
        return id;
    }
}
