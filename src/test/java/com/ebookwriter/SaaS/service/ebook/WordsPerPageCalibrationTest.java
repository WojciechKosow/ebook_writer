package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the length economy against the real 6×9" layout: that
 * {@link ChapterGenerationService#WORDS_PER_PAGE} still matches how many words
 * actually fit on a page (so generated length tracks the requested page count),
 * and that an over-length book is trimmed down to its page budget rather than
 * delivered — and billed — beyond it.
 */
class WordsPerPageCalibrationTest {

    private static final String[] WORDS = ("the quick brown fox jumps over a lazy dog while distant "
            + "mountains fade into a pale morning sky and the river carries small boats toward town "
            + "where people gather to trade stories about work family and the slow turning seasons")
            .split(" ");

    private static String prose(int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            sb.append(WORDS[i % WORDS.length]).append(' ');
            if (i > 0 && i % 80 == 0) sb.append("\n\n");
        }
        return sb.toString();
    }

    private static List<EbookChapter> chapters(int count, int wordsEach) {
        List<EbookChapter> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(EbookChapter.builder()
                    .chapterNumber(i).title("Chapter " + i).content(prose(wordsEach)).build());
        }
        return list;
    }

    private static int pages(PdfGenerationService pdf, Ebook ebook, List<EbookChapter> chapters)
            throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.render(ebook, chapters))) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void wordsPerPageConstantMatchesTheActualLayout() throws IOException {
        PdfGenerationService pdf = new PdfGenerationService(null, null, null, new EbookHtmlBuilder());
        Ebook ebook = Ebook.builder().topic("Calibration").title("Calibration Book").build();

        int totalWords = 2000;
        List<EbookChapter> chapters = chapters(4, totalWords / 4);
        int contentPages = pages(pdf, ebook, chapters) - EbookHtmlBuilder.FRONT_MATTER_PAGES;
        double observed = (double) totalWords / contentPages;

        // The constant is our estimate of this number; if the CSS changes enough
        // to move it out of a sane band, the length economy breaks — fail loudly.
        assertTrue(observed > 130 && observed < 300,
                "observed words/content-page (" + Math.round(observed) + ") drifted from the layout; "
                        + "revisit ChapterGenerationService.WORDS_PER_PAGE (currently "
                        + ChapterGenerationService.WORDS_PER_PAGE + ")");
    }

    @Test
    void overLengthBookIsTrimmedToItsPageBudget() throws IOException {
        PdfGenerationService pdf = new PdfGenerationService(null, null, null, new EbookHtmlBuilder());
        Ebook ebook = Ebook.builder().topic("Big").title("A Very Long Book").build();

        // ~6000 words → well over 20 pages, against an 8-page budget.
        List<EbookChapter> chapters = chapters(6, 1000);
        int budget = 8;

        int pageCount = pages(pdf, ebook, chapters);
        assertTrue(pageCount > budget, "precondition: the untrimmed book must exceed the budget");

        // Mirror renderAndStore's trim loop.
        int passes = 0;
        while (pageCount > budget && passes < 12) {
            int overflow = (pageCount - budget) * ChapterGenerationService.WORDS_PER_PAGE;
            if (!PdfGenerationService.trimTrailingWords(chapters, overflow)) break;
            pageCount = pages(pdf, ebook, chapters);
            passes++;
        }

        assertTrue(pageCount <= budget,
                "trimmed book (" + pageCount + " pages) must fit the budget of " + budget);
    }

    @Test
    void trimTrailingWordsSpillsAcrossChaptersAndReportsWhenEmpty() {
        List<EbookChapter> chapters = chapters(2, 240); // ~3 paragraphs each

        // Remove more than the last chapter holds — it must spill into the first.
        boolean removed = PdfGenerationService.trimTrailingWords(chapters, 300);
        assertTrue(removed);
        assertTrue(chapters.get(1).getContent().isBlank(), "last chapter fully consumed");
        assertFalse(chapters.get(0).getContent().isBlank(), "first chapter partially kept");

        // Draining what remains, then asking again, reports nothing left to trim.
        PdfGenerationService.trimTrailingWords(chapters, 10_000);
        assertFalse(PdfGenerationService.trimTrailingWords(chapters, 100),
                "nothing left to trim once every chapter is empty");
        assertEquals("", chapters.get(0).getContent());
    }
}
