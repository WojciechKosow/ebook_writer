package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The opener title de-duplicates the unit label, and the cover composes separate,
 * editable elements (real title/subtitle text + an image asset) with a safe
 * layout fallback when no visual is present.
 */
class EbookHtmlBuilderTest {

    @Test
    void resolveLayoutFallsBackToTypographicWithoutAnImage() {
        assertEquals(CoverLayout.TYPOGRAPHIC, EbookHtmlBuilder.resolveLayout(CoverLayout.IMAGE_LED, false));
        assertEquals(CoverLayout.TYPOGRAPHIC, EbookHtmlBuilder.resolveLayout(null, false));
        // An image with no chosen layout renders image-led; a real visual layout is kept.
        assertEquals(CoverLayout.IMAGE_LED, EbookHtmlBuilder.resolveLayout(null, true));
        assertEquals(CoverLayout.EDITORIAL, EbookHtmlBuilder.resolveLayout(CoverLayout.EDITORIAL, true));
        // A typographic choice but an image exists -> show the image.
        assertEquals(CoverLayout.IMAGE_LED, EbookHtmlBuilder.resolveLayout(CoverLayout.TYPOGRAPHIC, true));
    }

    @Test
    void coverKeepsTitleAndSubtitleAsRealTextWithSeparateImage() {
        EbookHtmlBuilder builder = new EbookHtmlBuilder();
        Ebook ebook = Ebook.builder()
                .topic("Focus").title("The 7-Day Focus Reset").subtitle("Stop checking your phone")
                .authorName("Alex Rivera").coverLayout(CoverLayout.EDITORIAL).build();
        UUID coverId = UUID.randomUUID();
        EbookImage cover = EbookImage.builder().id(coverId).ebook(ebook)
                .role(EbookImageRole.COVER).placement(EbookImagePlacement.COVER)
                .storageKey("k.png").contentType("image/png").build();
        EbookChapter c = EbookChapter.builder().chapterNumber(1).title("One").content("Body").build();

        String html = builder.build(ebook, List.of(c), "", List.of(cover));

        assertTrue(html.contains("cover--editorial"), "renders the chosen layout");
        assertTrue(html.contains("The 7-Day Focus Reset"), "title stays real text");
        assertTrue(html.contains("Stop checking your phone"), "subtitle stays real text");
        assertTrue(html.contains("by Alex Rivera"), "author byline is real text");
        assertTrue(html.contains("ebook-image:" + coverId), "the visual is a separate image asset");
        assertTrue(html.contains("SCRIVETTA") && !html.contains("SCRIVETTE"), "imprint is Scrivetta");
    }

    @Test
    void coverWithoutImageRendersTypographicNotBroken() {
        EbookHtmlBuilder builder = new EbookHtmlBuilder();
        Ebook ebook = Ebook.builder().topic("Focus").title("Focus")
                .coverLayout(CoverLayout.IMAGE_LED) // asked for an image, but none exists
                .build();
        EbookChapter c = EbookChapter.builder().chapterNumber(1).title("One").content("Body").build();

        String html = builder.build(ebook, List.of(c), "", List.of());

        assertTrue(html.contains("cover--typographic"), "safe fallback when the visual is missing");
        assertFalse(html.contains("cover-bg"), "no dangling image element");
    }

    @Test
    void stripsUnitPrefixOnlyForUnitOpeners() {
        assertEquals("Remove the Biggest Distractions",
                EbookHtmlBuilder.displayTitle("Day 1 — Remove the Biggest Distractions", true));
        assertEquals("Build a Focus Block",
                EbookHtmlBuilder.displayTitle("Day 2: Build a Focus Block", true));
        // Non-unit chapters are never stripped.
        assertEquals("Day 1 — Remove the Biggest Distractions",
                EbookHtmlBuilder.displayTitle("Day 1 — Remove the Biggest Distractions", false));
    }

    @Test
    void keepsOriginalWhenStrippingWouldEmptyIt() {
        assertEquals("Day 1", EbookHtmlBuilder.displayTitle("Day 1", true));
    }
}
