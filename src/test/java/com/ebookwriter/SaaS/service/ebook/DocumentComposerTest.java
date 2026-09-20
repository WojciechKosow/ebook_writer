package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.DocumentComposer.ChapterLayout;
import com.ebookwriter.SaaS.service.ebook.DocumentComposer.OpenerStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page-type composer decides "book moments" from structure, not topic, and
 * must never pad a short or tightly-budgeted book with dedicated opener pages.
 */
class DocumentComposerTest {

    private static EbookChapter ch(int n, String title, int pages, String content) {
        return EbookChapter.builder().chapterNumber(n).title(title).approxPages(pages)
                .description("A short scope sentence for chapter " + n + ".").content(content).build();
    }

    private static List<EbookChapter> program(int budgetPad) {
        List<EbookChapter> cs = new ArrayList<>();
        cs.add(ch(1, "Why Your Attention Is Broken", 2, "intro body"));
        cs.add(ch(2, "Day 1 — Remove the Biggest Distractions", 2, "day one body"));
        cs.add(ch(3, "Day 2 — Build a Focus Block", 2, "day two body"));
        cs.add(ch(4, "Day 3 — The 30-Minute Session", 2, "day three body"));
        return cs;
    }

    private static ChapterLayout byNumber(List<ChapterLayout> layouts, int n) {
        return layouts.stream().filter(l -> l.chapterNumber() == n).findFirst().orElseThrow();
    }

    @Test
    void programBookGivesFullPageDayOpenersWhenBudgetAllows() {
        Ebook ebook = Ebook.builder().title("The 7-Day Focus Reset").pageBudget(0).build(); // 0 = unknown -> allow
        List<ChapterLayout> layouts = DocumentComposer.compose(ebook, program(0));

        // The day chapters are units with dedicated opener pages.
        for (int n : new int[]{2, 3, 4}) {
            ChapterLayout l = byNumber(layouts, n);
            assertTrue(l.unit(), "chapter " + n + " should be a program unit");
            assertEquals(OpenerStyle.FULL_PAGE, l.style(), "day chapter should get a full-page opener");
        }
        // Labels + numerals are derived from the day number in the title.
        assertEquals("DAY 01", byNumber(layouts, 2).label());
        assertEquals("01", byNumber(layouts, 2).numeral());
        assertEquals("DAY 03", byNumber(layouts, 4).label());

        // The non-unit intro chapter stays a compact band (no wasted page).
        ChapterLayout intro = byNumber(layouts, 1);
        assertFalse(intro.unit());
        assertEquals(OpenerStyle.BAND, intro.style());
    }

    @Test
    void tightBudgetDowngradesDayOpenersToBands() {
        // Content already fills the budget: no room for extra opener pages.
        Ebook ebook = Ebook.builder().title("The 7-Day Focus Reset").pageBudget(10).build();
        List<ChapterLayout> layouts = DocumentComposer.compose(ebook, program(0));
        for (int n : new int[]{2, 3, 4}) {
            ChapterLayout l = byNumber(layouts, n);
            assertTrue(l.unit(), "still recognised as a unit (for band styling + label)");
            assertEquals(OpenerStyle.BAND, l.style(), "but no dedicated page when the budget is tight");
        }
    }

    @Test
    void shortBookNeverGetsFullPageOpeners() {
        Ebook ebook = Ebook.builder().title("A Focus Guide").pageBudget(0).build();
        List<EbookChapter> two = List.of(
                ch(1, "Part One", 5, "body one"),
                ch(2, "Part Two", 5, "body two"));
        List<ChapterLayout> layouts = DocumentComposer.compose(ebook, two);
        assertTrue(layouts.stream().allMatch(l -> l.style() == OpenerStyle.BAND),
                "a two-chapter book must stay compact");
    }

    @Test
    void substantialNonProgramBookGetsChapterOpeners() {
        Ebook ebook = Ebook.builder().title("The Craft of Deep Work").pageBudget(0).build();
        List<EbookChapter> cs = List.of(
                ch(1, "Foundations", 3, "b"),
                ch(2, "Attention", 3, "b"),
                ch(3, "Systems", 3, "b"),
                ch(4, "Mastery", 3, "b"));
        List<ChapterLayout> layouts = DocumentComposer.compose(ebook, cs);
        assertTrue(layouts.stream().allMatch(l -> l.style() == OpenerStyle.FULL_PAGE));
        assertTrue(layouts.stream().noneMatch(ChapterLayout::unit), "plain chapters, not program units");
        assertEquals("CHAPTER 01", byNumber(layouts, 1).label());
    }

    @Test
    void blankChaptersAreExcluded() {
        Ebook ebook = Ebook.builder().title("Focus").pageBudget(0).build();
        List<EbookChapter> cs = new ArrayList<>(List.of(
                ch(1, "Real", 2, "content"),
                ch(2, "Empty", 2, "   ")));
        List<ChapterLayout> layouts = DocumentComposer.compose(ebook, cs);
        assertEquals(1, layouts.size());
        assertEquals(1, layouts.get(0).chapterNumber());
    }

    @Test
    void canAffordReflectsSlackAgainstBudget() {
        assertTrue(DocumentComposer.canAfford(3, 5, 0), "unknown budget always affords");
        assertTrue(DocumentComposer.canAfford(3, 10, 15), "slack 3 >= ceil(3*0.6)=2");
        assertFalse(DocumentComposer.canAfford(3, 14, 16), "slack 0 cannot afford openers");
    }

    @Test
    void detectUnitParsesDigitsAndRomanNumerals() {
        assertEquals(3, DocumentComposer.detectUnit("Day 3 — Something").number());
        assertEquals("day", DocumentComposer.detectUnit("Day 3 — Something").word());
        assertEquals(2, DocumentComposer.detectUnit("Step 2: Begin").number());
        assertEquals(2, DocumentComposer.detectUnit("Part II — The Middle").number());
        org.junit.jupiter.api.Assertions.assertNull(DocumentComposer.detectUnit("A Normal Chapter"));
    }

    @Test
    void durationChipIsExtractedFromScopeOrBody() {
        EbookChapter c = EbookChapter.builder().title("Day 1")
                .description("Remove distractions. This takes 10–20 minutes.").content("body").build();
        assertEquals("10–20 MINUTES", DocumentComposer.duration(c));

        EbookChapter single = EbookChapter.builder().title("Day 2")
                .description("Quick one.").content("It needs 1 minute of setup.").build();
        assertEquals("1 MINUTE", DocumentComposer.duration(single));

        EbookChapter none = EbookChapter.builder().title("Day 3")
                .description("No time given.").content("body").build();
        org.junit.jupiter.api.Assertions.assertNull(DocumentComposer.duration(none));
    }
}
