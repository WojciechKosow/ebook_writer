package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.entity.WritingQuality;
import com.ebookwriter.SaaS.prompt.BriefPrompts;
import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.service.credit.CreditPricing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Basic model: the premium model's chapter briefs, and the two page prices. */
class ChapterBriefServiceTest {

    @Test
    void briefsAreReadPerChapterFromTheModelsJson() {
        Map<Integer, String> briefs = ChapterBriefService.parse("""
                Here you go:
                {"chapters": [{"chapterNumber": 1, "brief": "## Window light\\n- 0.5–1.5 m"},
                              {"chapterNumber": 2, "brief": "## Side lighting"},
                              {"chapterNumber": 0, "brief": "ignored"}]}
                """);
        assertEquals(Map.of(1, "## Window light\n- 0.5–1.5 m", 2, "## Side lighting"), briefs);
        assertThrows(IllegalStateException.class, () -> ChapterBriefService.parse("no json"));
    }

    @Test
    void anOverlongBriefIsCutAtALineBreakAndAnEmptyOneIsDropped() {
        String longBrief = ("- a point of the plan\n").repeat(400);
        String kept = ChapterBriefService.acceptable(longBrief);
        assertTrue(kept.length() <= ChapterBriefService.MAX_BRIEF_CHARS);
        assertTrue(kept.endsWith("plan"), "cut at a line break, not mid-line");
        assertNull(ChapterBriefService.acceptable("  "));
    }

    @Test
    void theBriefRequestCarriesEveryChapterAndItsMaterial() {
        var e = com.ebookwriter.SaaS.entity.Ebook.builder().topic("Portraits in natural light").build();
        String user = BriefPrompts.user(e, "Title: Portraits", "1. Light\n2. Settings", List.of(
                new BriefPrompts.ChapterInput(1, "Light", "Window light", 1200, "f/2–f/2.8 at a window"),
                new BriefPrompts.ChapterInput(2, "Settings", "Exposure", 900, "")));
        assertTrue(user.contains("=== Chapter 1: Light ==="));
        assertTrue(user.contains("=== Chapter 2: Settings ==="));
        assertTrue(user.contains("f/2–f/2.8 at a window"));
        assertTrue(BriefPrompts.system("Polish", 350).contains("The book itself is written in Polish"));
    }

    @Test
    void theWriterIsToldToFollowTheBriefOnlyWhenThereIsOne() {
        String with = ChapterPrompts.bookContextSections(new WritingContext("", null, "## Window light"), 1);
        String without = ChapterPrompts.bookContextSections(new WritingContext("", null), 1);
        assertTrue(with.contains("WRITING BRIEF FROM THE LEAD AUTHOR") && with.contains("## Window light"));
        assertFalse(without.contains("WRITING BRIEF"));
    }

    @Test
    void theBasicModelIsCheaperPerPageThanTheBetterModel() {
        CreditPricing pricing = new CreditPricing(new CreditProperties(), new OpenAiProperties());
        assertEquals(11, pricing.creditsPerPage(WritingQuality.BETTER), "the standard price");
        assertEquals(7, pricing.creditsPerPage(WritingQuality.BASIC));
        assertEquals(11, pricing.creditsPerPage(null), "older books are Better-model books");
        // Starting needs the low end only (no images configured here).
        assertEquals(20 * 7, pricing.minimumToStart(20, 7));
    }
}
