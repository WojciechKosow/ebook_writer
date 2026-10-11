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

    /** A chapter's first block starts under its opener unguarded, so blocks under test follow an opening line. */
    private static final String OPENING = "Opening line.\n\n";
    private static final String OPENING_HTML = "<p>Opening line.</p>\n";

    @Test
    void theChapterOpeningBlockIsNotGuardedItStartsUnderTheOpener() {
        String html = renderer.toHtml("### First\n\n" + "word ".repeat(300));
        assertTrue(html.startsWith("<div class=\"keep-with-next\"><h3>First</h3>"), html);
        assertFalse(html.contains("page-break-min-height"), html);
    }

    private static final String GROUP = "<div class=\"keep-with-next keep-with-next--start\" style=\"-fs-page-break-min-height: ";

    /** The start guard (pt) of the logical block group the HTML begins with, or -1. */
    private static int guard(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("keep-with-next--start\" style=\"-fs-page-break-min-height: (\\d+)pt").matcher(html);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Test
    void aHeadingIsKeptWithTheBlockItIntroduces() {
        String html = renderer.toHtml(OPENING + "## The audit\n\nList every app you opened today.\n\nAnother paragraph.");
        assertTrue(html.startsWith(OPENING_HTML + GROUP), html);
        assertTrue(html.contains("<h2>The audit</h2><p>List every app you opened today.</p>"),
                "heading and its first block form one logical block: " + html);
        assertEquals(1, countOccurrences(html, "keep-with-next--start"));
    }

    @Test
    void aHeadingIsKeptWithAComponentThatFollowsIt() {
        String html = renderer.toHtml(OPENING + "### Try it\n\n:::exercise The audit\nList every app.\n:::");
        assertTrue(html.startsWith(OPENING_HTML + GROUP), html);
        assertTrue(html.indexOf("keep-with-next") < html.indexOf("cmp--exercise"));
    }

    @Test
    void aLeadInLineTravelsWithTheCodeItIntroducesAndItsHeading() {
        String html = renderer.toHtml(OPENING + "### Offer\n\nHere is the entity:\n\n```java\nclass Offer {}\n```\n\nAfter.");
        assertTrue(html.contains("<h3>Offer</h3><p>Here is the entity:</p><pre>"),
                "heading, lead-in and code block form one unit: " + html);
        assertTrue(html.indexOf("</pre></div>") < html.indexOf("<p>After."));
    }

    @Test
    void aHeadingRunIsKeptWithItsFirstBlock() {
        String html = renderer.toHtml(OPENING + "## Data model\n\n### User\n\n```java\nclass User {}\n```");
        assertTrue(html.contains("<h2>Data model</h2><h3>User</h3><pre>"), html);
        assertEquals(1, countOccurrences(html, "keep-with-next--start"));
    }

    @Test
    void aSplittableTableJoinsItsHeadingByItsHeaderAndFirstRow() {
        String bigTable = "| A | B |\n|---|---|\n" + "| a | b |\n".repeat(40);
        String html = renderer.toHtml(OPENING + "### Columns\n\n" + bigTable);
        assertTrue(html.startsWith(OPENING_HTML + GROUP) && html.contains("<h3>Columns</h3><table"), html);
        assertTrue(guard(html) < 150, "the guard covers the heading, header and first row, not 40 rows: " + guard(html));
    }

    @Test
    void theGuardCoversOnlyTheStartOfALongBlockNotAllOfIt() {
        String longPara = "word ".repeat(400);
        int longGuard = guard(renderer.toHtml(OPENING + "## Heading\n\n" + longPara));
        int shortGuard = guard(renderer.toHtml(OPENING + "## Heading\n\nword word word."));
        assertTrue(longGuard > shortGuard, "the first lines of a long paragraph join the heading");
        assertTrue(longGuard < 150, "heading + first lines, not a page-long paragraph pushed forward: " + longGuard);

        StringBuilder hugeCode = new StringBuilder("```java\n");
        for (int i = 0; i < 90; i++) {
            hugeCode.append("int field").append(i).append(";\n");
        }
        int codeGuard = guard(renderer.toHtml(OPENING + "### Provider\n\n" + hugeCode + "```"));
        assertTrue(codeGuard > 0 && codeGuard < 160, "a code block taller than a page joins by its first lines: " + codeGuard);
    }

    @Test
    void aLabelIsKeptWithItsContent() {
        for (String label : new String[]{"**Request**", "**Important**", "Your task:", "The endpoints:"}) {
            String html = renderer.toHtml("Intro paragraph.\n\n" + label + "\n\n```http\nPOST /api/auth/login\n```");
            assertTrue(html.contains(GROUP) && html.indexOf("keep-with-next") < html.indexOf("<pre>"),
                    label + " forms a logical block with what follows: " + html);
        }
        assertFalse(renderer.toHtml(OPENING + "A plain sentence that is not a label.\n\nNext.").contains("keep-with-next"));
    }

    @Test
    void anExerciseOrOversizedComponentGuardsItsTitleWithTheStartOfItsBody() {
        String exercise = renderer.toHtml(OPENING + ":::exercise Implement login\nYour task: " + "word ".repeat(600) + "\n:::");
        assertTrue(exercise.contains("cmp cmp--exercise cmp--flows\" style=\"-fs-page-break-min-height: "),
                "an exercise flows over pages once its title and opening lines are placed: " + exercise);
        String note = renderer.toHtml(OPENING + ":::note\nShort note.\n:::");
        assertFalse(note.contains("page-break-min-height"), "a small callout moves whole; no guard needed");
    }

    // ---- Structural blocks: many, nested, inside lists, inside code ---------------

    @Test
    void manyComponentsInOneChapterEachRenderOnceWithNoStrayDigits() {
        StringBuilder md = new StringBuilder("Intro paragraph.\n\n");
        for (int i = 0; i < 14; i++) {
            md.append(":::exercise Task number ").append(i).append("\nDo thing ").append(i).append(".\n:::\n\n");
        }
        String html = renderer.toHtml(md.toString());
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(html);
        assertEquals(14, doc.select("div.cmp--exercise").size());
        for (int i = 0; i < 14; i++) {
            final int n = i;
            assertEquals(1, doc.select(".cmp-title").stream().filter(t -> t.text().equals("Task number " + n)).count());
        }
        assertTrue(doc.select("p").stream().noneMatch(p -> p.text().matches("\\d+")), html);
        assertFalse(html.contains("CMPBLOCKPLACEHOLDER"));
    }

    @Test
    void aBoxInsideAListItemStaysInItAndTheListContinues() {
        String md = "1. First step.\n2. Second step.\n\n   :::tip\n   A tip for step two.\n   :::\n\n3. Third step.\n";
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(renderer.toHtml(md));
        assertEquals(1, doc.select("ol").size(), doc.body().html());
        assertEquals(3, doc.select("ol > li").size());
        assertEquals(1, doc.select("ol > li:nth-child(2) div.cmp--tip").size());
    }

    @Test
    void boxesNestAndColonsInsideCodeAreCode() {
        String md = ":::exercise Outer\nDo the outer task.\n\n:::warning\nCareful inside.\n:::\n\nThen finish.\n:::\n\n"
                + "```text\n:::not-a-box\nliteral\n:::\n```\n";
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(renderer.toHtml(md));
        assertEquals(1, doc.select("div.cmp--exercise div.cmp--warning").size(), doc.body().html());
        assertTrue(doc.selectFirst("div.cmp--exercise").text().contains("Then finish."));
        assertTrue(doc.selectFirst("pre").wholeText().contains(":::not-a-box\nliteral\n:::"));
    }

    @Test
    void aVerbatimBlockKeepsEveryLineBreakAndIndent() {
        String md = ":::verbatim Title\nfirst line\n    indented line\n\nafter a blank line\n:::\n";
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(renderer.toHtml(md));
        org.jsoup.nodes.Element pre = doc.selectFirst("pre.verbatim--text");
        assertEquals("first line\n    indented line\n\nafter a blank line", pre.wholeText());
        assertEquals("Title", doc.selectFirst("p.verbatim-title").text());
    }

    @Test
    void anEmptyHeadingAndARepeatedBoxAreNotPrinted() {
        RenderContext ctx = new RenderContext();
        String box = ":::warning\nNever skip the backup before an upgrade, whatever the deadline says.\n:::\n\n";
        ctx.startChapter(1);
        renderer.toHtml("Intro.\n\n" + box, ctx);
        ctx.startChapter(2);
        String html = renderer.toHtml("Intro two.\n\n## Empty\n\n## Full\n\nText.\n\n" + box, ctx);
        assertFalse(html.contains("Empty"));
        assertFalse(html.contains("Never skip the backup"));
        assertEquals(java.util.List.of(RenderContext.NoteType.DUPLICATE_REMOVED, RenderContext.NoteType.EMPTY_HEADING_REMOVED),
                ctx.notes().stream().map(RenderContext.Note::type).toList());
    }
}
