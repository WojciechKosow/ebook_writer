package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The final quality gate. Fatal issues (no pages, no content) fail a book so it
 * never becomes a finished product; shape/asset concerns are recorded as warnings
 * but never fail an otherwise-valid book.
 */
class EbookValidationServiceTest {

    private static Ebook book(CoverLayout layout) {
        return Ebook.builder().title("The 7-Day Focus Reset").coverLayout(layout).build();
    }

    private static EbookChapter chapter(String content) {
        return EbookChapter.builder().chapterNumber(1).title("One").content(content).build();
    }

    private static EbookImage cover(int w, int h) {
        return EbookImage.builder().id(UUID.randomUUID())
                .placement(EbookImagePlacement.COVER).storageKey("k").width(w).height(h).build();
    }

    private static boolean hasWarningContaining(EbookValidationService.Report r, String needle) {
        return r.warnings().stream().anyMatch(i -> i.message().contains(needle));
    }

    @Test
    void aWellFormedBookPasses() {
        // A landscape cover (1536x1024) matches EDITORIAL's landscape region.
        EbookValidationService.Report r = EbookValidationService.inspect(
                book(CoverLayout.EDITORIAL), List.of(chapter("Real content here.")),
                List.of(cover(1536, 1024)), 30);
        assertFalse(r.hasFatal());
        assertTrue(r.warnings().isEmpty(), "a matched cover raises no warnings");
    }

    @Test
    void noPagesIsFatal() {
        EbookValidationService.Report r = EbookValidationService.inspect(
                book(CoverLayout.TYPOGRAPHIC), List.of(chapter("x")), List.of(), 0);
        assertTrue(r.hasFatal());
    }

    @Test
    void noRenderedContentIsFatal() {
        EbookValidationService.Report r = EbookValidationService.inspect(
                book(CoverLayout.TYPOGRAPHIC), List.of(chapter("   ")), List.of(), 12);
        assertTrue(r.hasFatal());
    }

    @Test
    void missingCoverVisualIsAWarningNotFatal() {
        EbookValidationService.Report r = EbookValidationService.inspect(
                book(CoverLayout.EDITORIAL), List.of(chapter("content")), List.of(), 20);
        assertFalse(r.hasFatal());
        assertTrue(hasWarningContaining(r, "expects a visual"));
    }

    @Test
    void typographicCoverWithoutImageIsFine() {
        EbookValidationService.Report r = EbookValidationService.inspect(
                book(CoverLayout.TYPOGRAPHIC), List.of(chapter("content")), List.of(), 20);
        assertFalse(r.hasFatal());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void mismatchedCoverAspectRatioIsAWarning() {
        // A portrait asset (1024x1536) forced into EDITORIAL's landscape region.
        EbookValidationService.Report r = EbookValidationService.inspect(
                book(CoverLayout.EDITORIAL), List.of(chapter("content")),
                List.of(cover(1024, 1536)), 20);
        assertFalse(r.hasFatal());
        assertTrue(hasWarningContaining(r, "does not match layout"));
    }
}
