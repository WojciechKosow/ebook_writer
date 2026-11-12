package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.KnowledgeWritingProperties;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Chapter;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.KeyPoint;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.prompt.BlueprintPrompts;
import com.ebookwriter.SaaS.prompt.ImagePlanningPrompts;
import com.ebookwriter.SaaS.prompt.KnowledgeChapterPrompts;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeChunker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.ebookwriter.SaaS.entity.BookDepth.COMPREHENSIVE;
import static com.ebookwriter.SaaS.entity.BookDepth.QUICK;
import static com.ebookwriter.SaaS.entity.BookDepth.STANDARD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scrivetta is not only for programmers. A portrait photographer pastes about
 * eight pages of personal notes (≈3,000 words: exposure, natural light, gear,
 * workflow, common mistakes, exercises). The book must stay the photographer's
 * book — a sensible length for that material at every depth, never a 200-page
 * book "about light" — and the writer must be told about photography, not about
 * classes and endpoints.
 */
class NonTechnicalKnowledgeBookTest {

    private static final int MAX = 400;

    /** The photographer's notes as pasted (one section duplicated, as in the real upload). */
    private static final long NOTES_CHARS = 19_500;

    /** What the extraction typically finds in those notes. */
    private static final int KNOWLEDGE_UNITS = 70;

    /** A typical blueprint for them: nine chapters, each mapped to a good share of the notes. */
    private static final List<ScopeEstimator.ChapterSignal> NINE_CHAPTERS =
            Collections.nCopies(9, new ScopeEstimator.ChapterSignal(4, 6, 8, 1));

    private static ScopeEstimate estimate(BookDepth depth, List<ScopeEstimator.ChapterSignal> chapters) {
        return ScopeEstimator.estimate(new ScopeEstimator.Signals(depth, true, 300, 0,
                NOTES_CHARS, KNOWLEDGE_UNITS, chapters), MAX);
    }

    // ---- Length ---------------------------------------------------------------------

    @Test
    void eightPagesOfNotesMakeAModestBookAtEveryDepth() {
        ScopeEstimate quick = estimate(QUICK, NINE_CHAPTERS);
        ScopeEstimate standard = estimate(STANDARD, NINE_CHAPTERS);
        ScopeEstimate comprehensive = estimate(COMPREHENSIVE, NINE_CHAPTERS);

        assertTrue(quick.pagesHigh() <= 32, "quick: " + quick);
        assertTrue(standard.pagesHigh() <= 50, "standard: " + standard);
        assertTrue(comprehensive.pagesHigh() <= 80, "comprehensive: " + comprehensive);
        assertTrue(quick.pagesHigh() <= standard.pagesHigh() && standard.pagesHigh() < comprehensive.pagesHigh());
        // Still a real book: never squeezed below what nine chapters physically need.
        assertTrue(quick.pagesHigh() >= ScopeEstimator.structuralMinimumPages(9), "quick: " + quick);
    }

    @Test
    void withoutTheCeilingTheSameNotesWouldHaveBeenEstimatedFarLonger() {
        // The blueprint heuristic alone (no material ceiling) — what the photographer
        // would have seen before: 100+ pages at Comprehensive from eight pages of notes.
        double raw = NINE_CHAPTERS.stream()
                .mapToDouble(c -> ScopeEstimator.chapterPagesExact(COMPREHENSIVE, c)).sum();
        assertTrue(raw > 120, "raw blueprint sizing " + raw);
        assertTrue(estimate(COMPREHENSIVE, NINE_CHAPTERS).pagesHigh() < raw / 1.5);
    }

    @Test
    void aGenerousAiGuessCannotTurnTheNotesIntoABigBook() {
        for (BookDepth depth : BookDepth.values()) {
            ScopeEstimate heuristic = estimate(depth, NINE_CHAPTERS);
            ScopeEstimate combined = ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(180, 260), MAX);
            // The AI may widen the range, but not move it past what the material carries.
            double ceiling = ScopeEstimator.materialCeilingPages(depth, heuristic.sourcePages())
                    + EbookHtmlBuilder.FRONT_MATTER_PAGES;
            assertTrue(combined.midpoint() <= Math.max(heuristic.midpoint(), ceiling) + 3, // ranges round to 5 pages
                    depth + ": " + combined + " vs " + heuristic);
            assertTrue(combined.pagesHigh() < 100, depth + ": " + combined);
        }
    }

    @Test
    void aConservativeAiGuessStillPullsTheEstimateDown() {
        ScopeEstimate heuristic = estimate(STANDARD, NINE_CHAPTERS);
        ScopeEstimate combined = ScopeEstimator.combine(heuristic, new AiScopeAssessment.Range(26, 36), MAX);
        assertTrue(combined.pagesHigh() < heuristic.pagesHigh(), combined + " vs " + heuristic);
    }

    @Test
    void largeMaterialsAreNotLimitedByTheCeiling() {
        // 150 pages of a real project: the ceiling is far above every other bound.
        ScopeEstimate big = ScopeEstimator.estimate(new ScopeEstimator.Signals(COMPREHENSIVE, true, 300, 0,
                150 * (long) ScopeEstimator.SOURCE_CHARS_PER_PAGE, 150, List.of()), MAX);
        assertTrue(big.pagesHigh() > 150, big.toString());
        assertTrue(ScopeEstimator.materialCeilingPages(COMPREHENSIVE, 150) > big.pagesHigh());
    }

    @Test
    void thePlanNeverExceedsTheRangeTheUserAgreedTo() {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            chapters.add(chapter("c" + i, "Chapter " + i, List.of("topic a", "topic b", "topic c", "topic d"),
                    6, List.of(KnowledgeChunker.NOTES_REF)));
        }
        for (BookDepth depth : BookDepth.values()) {
            ScopeEstimate shown = estimate(depth, NINE_CHAPTERS);
            List<PlannedChapter> plan = KnowledgeBookPlanner.size(chapters, depth,
                    shown.midpoint() - EbookHtmlBuilder.FRONT_MATTER_PAGES,
                    shown.pagesHigh() - EbookHtmlBuilder.FRONT_MATTER_PAGES);
            int planned = BookPlanningService.totalPages(plan) + EbookHtmlBuilder.FRONT_MATTER_PAGES;
            assertTrue(planned <= shown.pagesHigh(), depth + ": plan " + planned + " > shown " + shown);
            assertTrue(plan.stream().allMatch(p -> p.approxPages() >= 1));
        }
    }

    // ---- The writer sees the photographer's own words -------------------------------

    @Test
    void aChapterQuotesTheNotesPassagesItIsAbout() {
        Chapter windowLight = chapter("c-window", "Window Light and Side Lighting",
                List.of("window light", "side lighting", "distance from the window"), 0,
                List.of(KnowledgeChunker.NOTES_REF));
        KnowledgeWritingProperties limits = new KnowledgeWritingProperties();
        Map<String, NormalizedDocument> docs = Map.of(KnowledgeChunker.NOTES_REF,
                new NormalizedDocument(KnowledgeChunker.NOTES_REF, Kind.NOTES, "txt", null,
                        PhotographerNotes.TEXT.length(), false, "h", PhotographerNotes.TEXT));

        String excerpt = KnowledgeChapterContext.notesExcerpt(windowLight, docs, limits);

        assertTrue(PhotographerNotes.TEXT.length() > limits.getMaxExcerptChars(), "the notes are longer than one excerpt");
        assertTrue(excerpt.startsWith("--- THE AUTHOR'S NOTES"));
        assertTrue(excerpt.contains("approximately 0.5–1.5 metres from a window"), "the author's own values, verbatim");
        assertTrue(excerpt.contains("Side lighting creates more dimension"));
        assertFalse(excerpt.contains("Overediting skin"), "another chapter's material is not quoted");
        assertTrue(excerpt.length() <= limits.getMaxExcerptChars() + 200);
    }

    @Test
    void aChapterThatDoesNotDrawOnTheNotesQuotesNothing() {
        Chapter other = chapter("c-x", "Window Light", List.of("window light"), 0, List.of("lighting.pdf"));
        Map<String, NormalizedDocument> docs = Map.of(KnowledgeChunker.NOTES_REF,
                new NormalizedDocument(KnowledgeChunker.NOTES_REF, Kind.NOTES, "txt", null,
                        PhotographerNotes.TEXT.length(), false, "h", PhotographerNotes.TEXT));
        assertEquals("", KnowledgeChapterContext.notesExcerpt(other, docs, new KnowledgeWritingProperties()));
    }

    // ---- The prompts speak about the author's field, not about code ------------------

    @Test
    void thePromptsDoNotAssumeTheAuthorIsAProgrammer() {
        String writer = KnowledgeChapterPrompts.system("English");
        assertFalse(writer.contains("The author has a real project"));
        assertTrue(writer.contains("photography"), "the writer is told the subject can be any field");
        assertTrue(writer.contains("do not add code"), "non-technical material gets no invented code");
        assertTrue(writer.contains("STAY WITHIN THE MATERIAL"));

        String editor = KnowledgeChapterPrompts.editingAddendum("Title: Portraits in Natural Light");
        assertFalse(editor.contains("OWN PROJECT"));

        String planner = BlueprintPrompts.system(8);
        assertTrue(planner.contains("Do not assume it is technical"));
        assertTrue(planner.contains("Size the structure to the material"));

        Ebook book = Ebook.builder().topic("Portrait photography with natural light").style("friendly").build();
        String guidelines = KnowledgeBookPlanner.writingGuidelines(book, blueprint());
        assertFalse(guidelines.toLowerCase().contains("project"), guidelines);

        String images = ImagePlanningPrompts.system("English", 6, 2);
        assertTrue(images.contains("would pass as real evidence"), "no AI 'example photos' in a photographer's book");
    }

    // ---- fixtures -----------------------------------------------------------------------

    private static Chapter chapter(String id, String title, List<String> topics, int keyPoints, List<String> sources) {
        List<KeyPoint> points = new ArrayList<>();
        for (int i = 0; i < keyPoints; i++) points.add(new KeyPoint("Key point " + i + " of " + title, sources));
        return new Chapter(id, 1, title, "Purpose of " + title, topics, points, List.of(), sources, List.of(), "AI", false);
    }

    private static BlueprintData blueprint() {
        return new BlueprintData(BlueprintData.CURRENT_SCHEMA_VERSION,
                "A practical guide to portraits in natural light.", "Portraits in Natural Light", null,
                "Beginner portrait photographers", "Make flattering portraits without a studio",
                "You will light and pose a portrait with one window.", "Light first, then settings, then workflow.",
                List.of(), List.of(), List.of());
    }

    /** About 5,000 characters of the photographer's notes — longer than one excerpt. */
    static final class PhotographerNotes {
        static final String TEXT = """
                Photography Fundamentals — Personal Notes
                My approach to portrait photography
                I believe that good portrait photography does not require an expensive studio or a collection of professional lights. Understanding light is much more important than owning expensive equipment.

                The exposure triangle
                Aperture controls how much light enters the camera through the lens. It also influences depth of field. For portraits, wider apertures such as f/1.8, f/2, or f/2.8 can help separate the subject from the background.
                Shutter speed controls how long the camera sensor is exposed to light. For a relatively still person, 1/125s can sometimes work. For more active subjects, 1/250s or faster is often safer.
                ISO controls the camera sensor's sensitivity to light. I would rather use ISO 800 and get a sharp photograph than use ISO 100 and produce a blurry image.

                Natural Light Techniques — Field Notes
                Window light
                Window light is one of the easiest ways to create attractive portraits indoors. A large window can act as a natural soft light source. The larger the apparent size of the light source relative to the subject, the softer the shadows tend to be.
                My preferred starting setup:
                1. Place the subject approximately 0.5–1.5 metres from a window.
                2. Turn the subject slightly toward the window.
                3. Avoid direct sunlight hitting the face when a softer result is desired.
                4. Position yourself at an angle where you can see the light shaping the face.
                5. Take a test photograph and check the highlights and shadows.

                Side lighting
                Side lighting creates more dimension because one side of the face receives more light than the other. I use this when I want a portrait with more depth and visible facial structure. The subject should not necessarily look directly at the window.

                Backlighting
                Backlighting happens when the main light source is behind the subject. This can create a beautiful glow around the hair and shoulders, especially outdoors. The main difficulty is exposure: if the camera exposes for the bright background, the subject may become too dark.

                Open shade
                Open shade is an area protected from direct sunlight but still receiving light from the open sky: the shadow side of a building, a covered outdoor area, a shaded street. Be careful with green or strongly coloured surroundings because reflected light can affect skin tones.

                Camera Settings and Equipment — Personal Notes
                A 50mm f/1.8 lens is particularly useful because it offers a wide maximum aperture without requiring a large investment. An 85mm lens allows comfortable working distance and is especially useful for tighter headshots. A 35mm lens is useful when I want to include more of the environment.
                Indoor window portrait: aperture f/2–f/2.8, shutter speed 1/125s or faster, ISO 400–800 as a starting range, eye autofocus where available.

                My Portrait Photography Workflow
                Before the session I ask what kind of photographs they want. I check the weather, charge the batteries, prepare memory cards, clean the lens and have a backup location.
                During the session I give small instructions: turn your shoulders slightly, look toward the window, relax your hands, shift your weight onto one leg.

                Common Portrait Photography Mistakes
                Mistake 1: Photographing in direct midday sunlight. Strong sunlight can create harsh shadows under the eyes and nose.
                Mistake 2: Always using the widest aperture. When photographing multiple people, the depth of field may become too shallow.
                Mistake 3: Ignoring the background. Inspect the entire frame before taking the photograph.
                Mistake 6: Overediting skin. Excessive skin smoothing can make portraits look artificial; keep skin texture and use retouching selectively.
                Mistake 8: Buying equipment instead of practising.
                My biggest recommendation: sometimes moving the subject 30 centimetres to one side makes a bigger difference than changing five settings on the camera.

                Practical Portrait Photography Exercises
                Exercise 1: One window, five portraits. Position the subject facing the window, then turn them approximately 45 degrees, then sideways, then move them farther away, and compare shadow intensity, catchlights and contrast.
                Exercise 2: The same portrait with three apertures: f/1.8, f/2.8 and f/5.6. Compare background blur and sharpness of the face.
                """;
    }
}
