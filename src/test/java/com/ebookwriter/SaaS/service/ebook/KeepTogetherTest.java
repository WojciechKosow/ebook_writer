package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logical blocks on rendered PDFs: every title — heading, intro heading, label,
 * lead-in, exercise or callout title — shares a page with the start of what it
 * introduces, wherever on the page it falls; large code blocks and exercises
 * still run over pages; and nothing moves to a new page when its start would
 * have fit.
 */
class KeepTogetherTest {

    private static final String S = "The token is signed with the server key and checked on every request. ";

    private final PdfGenerationService service =
            new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);

    private static String t(int n) {
        return KeepTogetherProbe.title(n);
    }

    private static String s(int n) {
        return KeepTogetherProbe.start(n);
    }

    private static String code(int n, int lines) {
        StringBuilder sb = new StringBuilder("```java\n// " + s(n) + "\npublic class JwtProvider {\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    private String field").append(i).append(";\n");
        }
        return sb.append("} // ").append(KeepTogetherProbe.end(n)).append("\n```").toString();
    }

    /** The structures under test, each a title and its content, as Markdown. */
    private static Map<String, IntFunction<String>> blocks() {
        Map<String, IntFunction<String>> m = new LinkedHashMap<>();
        m.put("heading + paragraph", n -> "### " + t(n) + " Authentication\n\n" + s(n) + " " + S.repeat(14));
        m.put("heading + intro", n -> "## " + t(n) + " JWT Authentication\n\n" + s(n) + " In this chapter we will implement it. "
                + S.repeat(5) + "\n\n" + S.repeat(6));
        m.put("heading + code", n -> "### " + t(n) + " Creating the JWT Provider\n\n" + code(n, 16));
        m.put("heading + large code", n -> "### " + t(n) + " Creating the JWT Provider\n\n" + code(n, 70));
        m.put("label + code", n -> "**" + t(n) + " Request**\n\n" + code(n, 6));
        m.put("lead-in + paragraph", n -> t(n) + " Your task:\n\n" + s(n) + " " + S.repeat(10));
        m.put("exercise", n -> ":::exercise " + t(n) + " Implement Login\n" + s(n) + " Your task: " + S.repeat(4)
                + "\n\n" + S.repeat(10) + "\n:::");
        m.put("large exercise", n -> ":::exercise " + t(n) + " Implement Login\n" + s(n) + " Your task: " + S.repeat(4)
                + "\n\n" + (S.repeat(10) + "\n\n").repeat(6) + KeepTogetherProbe.end(n) + "\n:::");
        m.put("callout", n -> ":::warning " + t(n) + "\n" + s(n) + " " + S.repeat(8) + "\n:::");
        m.put("large callout", n -> ":::tip " + t(n) + "\n" + s(n) + " " + (S.repeat(10) + "\n\n").repeat(6) + ":::");
        return m;
    }

    @Test
    void everyTitleSharesAPageWithTheStartOfItsContent() throws Exception {
        Map<Integer, String> label = new LinkedHashMap<>();
        List<EbookChapter> chapters = new ArrayList<>();
        int n = 1;
        for (var block : blocks().entrySet()) {
            for (int lines = 0; lines <= 36; lines += 2) { // every height on the page
                label.put(n, block.getKey() + " @" + lines);
                chapters.add(EbookChapter.builder().chapterNumber(n).title("Case " + n).content(
                        "Filler.\n\n".repeat(lines) + KeepTogetherProbe.before(n) + "\n\n" + block.getValue().apply(n)
                                + "\n\nAfter.").build());
                n++;
            }
        }
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), chapters);
        Map<Integer, KeepTogetherProbe.Placement> placed = KeepTogetherProbe.inspect(pdf);

        List<String> orphans = new ArrayList<>();
        List<String> wasteful = new ArrayList<>();
        int largeCodeSpans = 0;
        int largeExerciseSpans = 0;
        for (KeepTogetherProbe.Placement p : placed.values()) {
            String what = label.get(p.block());
            assertTrue(p.titlePage() > 0 && p.startPage() > 0, "markers found for " + what);
            if (p.orphaned()) {
                orphans.add(what + " " + p);
            }
            // Moved only when its start could not fit: a splittable block leaves at
            // most about its title and opening lines behind. (Code and callouts that
            // fit a page move whole, as they always have.)
            boolean splittableStart = !what.startsWith("heading + code") && !what.startsWith("label + code")
                    && !what.startsWith("callout");
            if (splittableStart && p.moved() && p.gapLeft() > 150) {
                wasteful.add(what + " left " + (int) p.gapLeft() + "pt");
            }
            if (what.startsWith("heading + large code") && p.endPage() > p.startPage()) {
                largeCodeSpans++;
            }
            if (what.startsWith("large exercise") && p.endPage() > p.startPage()) {
                largeExerciseSpans++;
            }
        }
        assertEquals(List.of(), orphans, "no title ends a page above content that begins overleaf");
        assertEquals(List.of(), wasteful, "no block moved whose start would have fit");
        assertEquals(19, largeCodeSpans, "a code block taller than a page still runs over pages");
        assertEquals(19, largeExerciseSpans, "a long exercise still runs over pages");
    }

    @Test
    void withRoomOnThePageNothingMoves() throws Exception {
        List<EbookChapter> chapters = new ArrayList<>();
        int n = 1;
        for (IntFunction<String> block : blocks().values()) {
            // A few lines down a fresh page: every block's start fits.
            chapters.add(EbookChapter.builder().chapterNumber(n).title("Case " + n).content(
                    "Filler.\n\n".repeat(3) + KeepTogetherProbe.before(n) + "\n\n" + block.apply(n)).build());
            n++;
        }
        for (KeepTogetherProbe.Placement p : KeepTogetherProbe.inspect(service.render(PaginationFixtureBook.ebook(), chapters)).values()) {
            assertEquals(p.beforePage(), p.titlePage(), "block " + p.block() + " stays where it is: " + p);
        }
    }

    @Test
    void aWholeBookHasNoOrphanedTitlesOfAnyKind() throws Exception {
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), PaginationFixtureBook.chapters(1, 10));

        assertEquals(List.of(), StrandedHeadingProbe.findTitles(pdf),
                "headings, labels, lead-ins, component titles and table headers all keep their content");
    }
}
