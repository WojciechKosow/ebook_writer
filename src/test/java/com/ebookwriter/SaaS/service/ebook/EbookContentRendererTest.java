package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content design system: directive blocks and natural-language patterns must
 * become styled, self-contained HTML components (shared by preview and PDF), and
 * ordinary Markdown must still render normally. Pure and offline.
 */
class EbookContentRendererTest {

    private final EbookContentRenderer renderer = new EbookContentRenderer();

    @Test
    void plainMarkdownStillRendersAndImageTokensSurvive() {
        String html = renderer.toHtml("A paragraph.\n\n![cap](ebook-image:abc)\n\n- one\n- two");
        assertTrue(html.contains("<p>A paragraph.</p>"));
        assertTrue(html.contains("ebook-image:abc"), "inline image tokens must pass through untouched");
        assertTrue(html.contains("<li>one</li>"));
        assertFalse(html.contains(":::"));
    }

    @Test
    void keyIdeaBlockBecomesLabelledComponent() {
        String html = renderer.toHtml("""
                Intro.

                :::key-idea
                Focus is a **state**, not a trait.
                :::

                Outro.
                """);
        assertTrue(html.contains("cmp--keyidea"));
        assertTrue(html.contains("KEY IDEA"));
        assertTrue(html.contains("<strong>state</strong>"), "inner Markdown must be rendered");
        assertTrue(html.contains("Intro.") && html.contains("Outro."));
        assertFalse(html.contains(":::"), "no raw fences should leak into the HTML");
    }

    @Test
    void flowBlockRendersProgrammaticDiagramWithArrows() {
        String html = renderer.toHtml("""
                :::flow
                Difficult work
                Discomfort
                Check phone
                Relief
                :::
                """);
        assertTrue(html.contains("cmp--flow"));
        assertEquals(4, countOccurrences(html, "flow-node"));
        assertEquals(3, countOccurrences(html, "flow-arrow"), "n nodes -> n-1 connectors");
        assertTrue(html.contains("Difficult work") && html.contains("Relief"));
    }

    @Test
    void flowAcceptsInlineArrowSeparators() {
        String html = renderer.toHtml(":::flow\nWork -> Discomfort -> Relief\n:::");
        assertEquals(3, countOccurrences(html, "flow-node"));
    }

    @Test
    void stepsBlockRendersNumberedActionPlanWithMeta() {
        String html = renderer.toHtml("""
                :::steps Day 1
                - Audit what pulls you away | 5 min
                  Watch which apps you open.
                - Turn off notifications | 15 min
                  Silence everything.
                :::
                """);
        assertTrue(html.contains("cmp--steps"));
        assertTrue(html.contains("DAY 1"), "the eyebrow title is uppercased");
        assertTrue(html.contains(">01<") && html.contains(">02<"), "steps are numbered 01, 02");
        assertTrue(html.contains("5 MIN") && html.contains("15 MIN"), "durations render as meta");
        assertTrue(html.contains("Watch which apps you open."));
    }

    @Test
    void doneWhenIsAutodetectedFromProseAndCapturesWholeParagraph() {
        // A soft-wrapped paragraph must be captured in full, not clipped mid-line.
        String html = renderer.toHtml(
                "Do the work.\n\nDone when: your phone can sit beside you for an hour\nwithout lighting up once.\n\nNext.");
        assertTrue(html.contains("cmp--donewhen"));
        assertTrue(html.contains("DONE WHEN"));
        assertTrue(html.contains("without lighting up once"),
                "the continuation line must be inside the component, not spilled outside");
        // 'Next.' stays an ordinary paragraph.
        assertTrue(html.contains("<p>Next.</p>"));
    }

    @Test
    void pullQuoteAndChecklistRender() {
        String pull = renderer.toHtml(":::pullquote\nConditions, not willpower.\n:::");
        assertTrue(pull.contains("cmp--pullquote"));

        String checklist = renderer.toHtml(":::checklist Before you start\n- Pick one task\n- Hide the phone\n:::");
        assertTrue(checklist.contains("cmp--checklist"));
        assertEquals(2, countOccurrences(checklist, "check-item"));
        assertTrue(checklist.contains("BEFORE YOU START"));
    }

    @Test
    void unknownDirectiveDegradesToGenericNoteWithoutLosingContent() {
        String html = renderer.toHtml(":::mystery\nStill important text.\n:::");
        assertTrue(html.contains("cmp--note"));
        assertTrue(html.contains("Still important text."));
    }

    @Test
    void unclosedBlockDoesNotThrowAndKeepsContent() {
        String html = renderer.toHtml(":::key-idea\nNo closing fence here.");
        assertTrue(html.contains("No closing fence here."));
        assertFalse(html.contains(":::"));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals("", renderer.toHtml(null));
        assertEquals("", renderer.toHtml("   "));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void anImageOnItsOwnLineBecomesAFigureWithItsCaption() {
        String html = renderer.toHtml("Text.\n\n![A desk cleared for deep work](ebook-image:abc)\n\nMore.");
        assertTrue(html.contains("<figure class=\"figure\">"));
        assertTrue(html.contains("<figcaption>A desk cleared for deep work</figcaption>"),
                "the caption lives inside the figure so it can never separate from the image");
        assertTrue(html.contains("ebook-image:abc"));
    }

    @Test
    void genericOrDescriptiveAltTextIsNotPrintedAsACaption() {
        assertFalse(renderer.toHtml("![Illustration](ebook-image:abc)").contains("figcaption"));
        String longAlt = "x".repeat(EbookContentRenderer.MAX_CAPTION_CHARS + 1);
        assertFalse(renderer.toHtml("![" + longAlt + "](ebook-image:abc)").contains("figcaption"));
    }

    @Test
    void aHeadingIsKeptWithTheBlockItIntroduces() {
        String html = renderer.toHtml("## The audit\n\nList every app you opened today.\n\nAnother paragraph.");
        assertTrue(html.contains("<div class=\"keep-with-next\"><h2>The audit</h2><p>List every app"),
                "heading and its first block travel together: " + html);
        assertEquals(1, countOccurrences(html, "keep-with-next"));
    }

    @Test
    void aHeadingIsKeptWithAComponentThatFollowsIt() {
        String html = renderer.toHtml("### Try it\n\n:::exercise The audit\nList every app.\n:::");
        assertTrue(html.contains("keep-with-next"));
        assertTrue(html.indexOf("keep-with-next") < html.indexOf("cmp--exercise"));
    }

    @Test
    void aVeryLongParagraphIsNotGluedToItsHeading() {
        String longPara = "word ".repeat(EbookContentRenderer.KEEP_WITH_NEXT_MAX_CHARS / 4);
        String html = renderer.toHtml("## Heading\n\n" + longPara);
        assertFalse(html.contains("keep-with-next"),
                "gluing a page-long paragraph would push a whole page forward for one heading");
    }
}
