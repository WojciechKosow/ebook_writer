package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.WritingQuality;
import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.ai.ModelChoice;
import com.ebookwriter.SaaS.service.ai.ModelRefusalException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A photographer's chapter the writer declines: the chapter is written again
 * without the notes and the AI's descriptions of its photos, then by the premium
 * model, and only fails — with a reason the author can act on — when every
 * variant is declined. A refusal never ends as "failed after 2 attempts".
 */
class ChapterRefusalFallbackTest {

    private final AnthropicProperties properties = new AnthropicProperties();
    private final ModelChoice basic = properties.writerFor(WritingQuality.BASIC);
    private final ModelChoice premium = properties.writerFor(WritingQuality.BETTER);
    private AnthropicService ai;
    private ChapterGenerationService writer;
    private GenreFixtures.Book book;
    private EbookChapter chapter;

    private void setUp() {
        book = GenreFixtures.all().get(0).copy();
        book.ebook().setWritingQuality(WritingQuality.BASIC);
        chapter = book.chapter(1);
        chapter.setStatus(ChapterStatus.PENDING);
        ai = mock(AnthropicService.class);
        EbookRepository ebooks = mock(EbookRepository.class);
        EbookChapterRepository chapters = mock(EbookChapterRepository.class);
        EbookImageRepository images = mock(EbookImageRepository.class);
        when(ebooks.findById(book.ebook().getId())).thenReturn(Optional.of(book.ebook()));
        when(chapters.findByEbookIdOrderByChapterNumberAsc(book.ebook().getId())).thenReturn(book.chapters());
        EbookImage photo = EbookImage.builder().id(UUID.randomUUID()).ebook(book.ebook()).storageKey("k/p.jpg")
                .contentType("image/jpeg").originalFilename("golden-hour.jpg").width(3000).height(2000)
                .placement(EbookImagePlacement.CHAPTER).role(EbookImageRole.ILLUSTRATION)
                .aiDescription("Portrait at golden hour")
                .authorNote("Next to the golden-hour section")
                .aiAnalysis("Warm low sun from behind, rim light on the hair, bare shoulders")
                .build();
        when(images.findByChapterId(any())).thenReturn(List.of(photo));
        writer = new ChapterGenerationService(ai, ebooks, chapters, images, null, null, null, properties);
    }

    private String response() {
        return chapter.getContent() + "\n\n" + ChapterPrompts.SUMMARY_DELIMITER + "\nWhat the chapter established.\n"
                + ChapterPrompts.TOPICS_DELIMITER + "\n- Main idea — what it means";
    }

    private static boolean detailed(String prompt) {
        return prompt != null && prompt.contains("What the image shows:");
    }

    @Test
    void aDeclinedChapterIsWrittenWithoutTheImageDetails() {
        setUp();
        when(ai.completeDetailed(anyString(), argThat(ChapterRefusalFallbackTest::detailed), anyLong(), eq(basic)))
                .thenThrow(new ModelRefusalException(basic.model()));
        when(ai.completeDetailed(anyString(), argThat(p -> p != null && !detailed(p)), anyLong(), eq(basic)))
                .thenReturn(new AnthropicService.Completion(response(), false));

        writer.generate(book.ebook().getId(), chapter.getId());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(ai, times(2)).completeDetailed(anyString(), prompts.capture(), anyLong(), eq(basic));
        String plain = prompts.getAllValues().get(1);
        assertTrue(plain.contains("Portrait at golden hour"), "the photo is still offered, by its caption");
        assertFalse(plain.contains("bare shoulders"), "without the AI's description of it");
        assertFalse(plain.contains("Next to the golden-hour section"), "and without the note");
        assertEquals(ChapterStatus.WRITTEN, chapter.getStatus());
    }

    @Test
    void thenThePremiumModelWritesIt() {
        setUp();
        when(ai.completeDetailed(anyString(), anyString(), anyLong(), eq(basic)))
                .thenThrow(new ModelRefusalException(basic.model()));
        when(ai.completeDetailed(anyString(), anyString(), anyLong(), eq(premium)))
                .thenReturn(new AnthropicService.Completion(response(), false));

        writer.generate(book.ebook().getId(), chapter.getId());

        verify(ai).completeDetailed(anyString(), argThat(p -> p != null && !detailed(p)), anyLong(), eq(premium));
        assertEquals(ChapterStatus.WRITTEN, chapter.getStatus());
    }

    @Test
    void whenEveryVariantIsDeclinedTheChapterFailsWithAReasonTheAuthorCanActOn() {
        setUp();
        when(ai.completeDetailed(anyString(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenThrow(new ModelRefusalException(basic.model()));

        ModelRefusalException e = assertThrows(ModelRefusalException.class,
                () -> writer.generate(book.ebook().getId(), chapter.getId()));

        assertTrue(e.getMessage().contains("declined to write this chapter"), e.getMessage());
        assertTrue(e.getMessage().contains("images placed in it"), e.getMessage());
        verify(ai, times(3)).completeDetailed(anyString(), anyString(), anyLong(), any(ModelChoice.class));
    }
}
