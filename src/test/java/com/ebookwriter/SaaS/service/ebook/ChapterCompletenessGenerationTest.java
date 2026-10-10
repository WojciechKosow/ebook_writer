package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Point 1 (no unit reaches the book incomplete) and the generation side of
 * points 2, 4, 5 and 6, on books of three genres. The model is replaced at the
 * {@link AnthropicService} boundary; everything else is the real writer.
 */
class ChapterCompletenessGenerationTest {

    static List<GenreFixtures.Book> books() {
        return GenreFixtures.all();
    }

    private AnthropicService ai;
    private EbookChapterRepository chapters;
    private ChapterGenerationService writer;

    private EbookChapter setUp(GenreFixtures.Book book, int chapterNumber) {
        ai = mock(AnthropicService.class);
        EbookRepository ebooks = mock(EbookRepository.class);
        chapters = mock(EbookChapterRepository.class);
        EbookImageRepository images = mock(EbookImageRepository.class);
        when(ebooks.findById(book.ebook().getId())).thenReturn(Optional.of(book.ebook()));
        when(chapters.findByEbookIdOrderByChapterNumberAsc(book.ebook().getId())).thenReturn(book.chapters());
        when(images.findByChapterId(any())).thenReturn(List.of());
        writer = new ChapterGenerationService(ai, ebooks, chapters, images, null, null, null);
        EbookChapter target = book.chapter(chapterNumber);
        target.setStatus(ChapterStatus.PENDING);
        return target;
    }

    private static String response(String content) {
        return content + "\n\n" + ChapterPrompts.SUMMARY_DELIMITER + "\nWhat the chapter established.\n"
                + ChapterPrompts.TOPICS_DELIMITER + "\n- Main idea — what it means\n- Second idea — why it matters";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aChapterCutOffByTheOutputLimitIsContinuedNotShortened(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter chapter = setUp(book, 1);
        String expected = chapter.getContent();
        String full = response(expected);
        int cut = expected.indexOf(' ', expected.length() / 2); // mid-sentence, mid-chapter
        when(ai.completeDetailed(anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(full.substring(0, cut), true));
        when(ai.continueFrom(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(full.substring(cut), false));

        writer.generate(book.ebook().getId(), chapter.getId());

        assertEquals(expected, chapter.getContent(), "the whole chapter, not a repaired stub");
        assertEquals(ChapterStatus.WRITTEN, chapter.getStatus());
        assertEquals("What the chapter established.", chapter.getSummary());
        assertTrue(chapter.getCoveredTopics().contains("Main idea — what it means"));
        verify(ai).continueFrom(anyString(), anyString(), eq(full.substring(0, cut)), anyLong());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aContinuationThatRepeatsItsLastWordsIsJoinedWithoutTheRepeat(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter chapter = setUp(book, 1);
        String expected = chapter.getContent();
        String full = response(expected);
        int cut = expected.indexOf(' ', expected.length() / 3);
        String restated = full.substring(cut - 40, cut) + full.substring(cut);
        when(ai.completeDetailed(anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(full.substring(0, cut), true));
        when(ai.continueFrom(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(restated, false));

        writer.generate(book.ebook().getId(), chapter.getId());

        assertEquals(expected, chapter.getContent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void anIncompleteChapterIsWrittenAgain(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter chapter = setUp(book, 1);
        String expected = chapter.getContent();
        String firstParagraph = expected.split("\n\n")[0];
        // The model stopped on its own, but after announcing something that never came.
        String broken = firstParagraph + "\n\nHere is how it looks:";
        when(ai.completeDetailed(anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(response(broken), false))
                .thenReturn(new AnthropicService.Completion(response(expected), false));

        writer.generate(book.ebook().getId(), chapter.getId());

        assertEquals(expected, chapter.getContent());
        verify(ai, times(2)).completeDetailed(anyString(), anyString(), anyLong());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aChapterThatStaysIncompleteFailsLoudlyAndIsNeverStored(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter chapter = setUp(book, 1);
        String original = chapter.getContent();
        chapter.setContent(null);
        String firstParagraph = original.split("\n\n")[0];
        String midSentence = firstParagraph.substring(0, firstParagraph.indexOf(' ', 30));
        when(ai.completeDetailed(anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(response(midSentence), false));

        ChapterGenerationService.IncompleteContentException e = assertThrows(
                ChapterGenerationService.IncompleteContentException.class,
                () -> writer.generate(book.ebook().getId(), chapter.getId()));

        assertTrue(e.getMessage().contains("ends mid-sentence"), e.getMessage());
        assertNull(chapter.getContent(), "no partial chapter is kept");
        verify(chapters, never()).save(any());
        verify(ai, times(ChapterGenerationService.MAX_ATTEMPTS)).completeDetailed(anyString(), anyString(), anyLong());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aChapterStillCutOffAfterEveryContinuationFails(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter chapter = setUp(book, 1);
        when(ai.completeDetailed(anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(chapter.getContent().substring(0, 200), true));
        when(ai.continueFrom(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(" and more", true));

        ChapterGenerationService.IncompleteContentException e = assertThrows(
                ChapterGenerationService.IncompleteContentException.class,
                () -> writer.generate(book.ebook().getId(), chapter.getId()));
        assertTrue(e.getMessage().contains("output limit"), e.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void laterChaptersAreWrittenWithTheRegistryTheTemplateTheLineLimitsAndTheBudget(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter chapter = setUp(book, 3);
        when(ai.completeDetailed(anyString(), anyString(), anyLong()))
                .thenReturn(new AnthropicService.Completion(response(chapter.getContent()), false));

        writer.generate(book.ebook().getId(), chapter.getId());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(ai).completeDetailed(system.capture(), user.capture(), anyLong());
        // Point 5: what earlier chapters explained, with the rule to refer back.
        String firstTopic = book.chapter(1).getCoveredTopics().split("\n")[0].replaceFirst("^- ", "");
        assertTrue(user.getValue().contains("TOPIC REGISTRY"));
        assertTrue(user.getValue().contains("Chapter 1: " + firstTopic), user.getValue());
        assertTrue(user.getValue().contains("Chapter 2: "));
        // Point 2: the book template — its sections, or the decision to have none.
        BookTemplate template = BookTemplate.fromJson(book.ebook().getChapterTemplateJson());
        assertTrue(user.getValue().contains("BOOK TEMPLATE"));
        if (template.isEmpty()) {
            assertTrue(user.getValue().contains("no recurring per-chapter sections"));
        } else {
            assertTrue(user.getValue().contains("## " + template.sections().get(0).heading()));
        }
        // Point 4: verbatim line limits measured on the real page.
        VerbatimLayout.Limits limits = VerbatimLayout.limits();
        assertTrue(system.getValue().contains("at most " + limits.monoChars()), system.getValue());
        assertTrue(system.getValue().contains(limits.textChars() + " characters"));
        // Point 6: the chapter's budget, with its tolerance.
        int words = ChapterGenerationService.plannedWords(chapter);
        assertTrue(user.getValue().contains("Length budget: about " + words + " words (acceptable range "
                + Math.round(words * 0.75) + "–" + Math.round(words * 1.25) + ")"), user.getValue());
    }
}
