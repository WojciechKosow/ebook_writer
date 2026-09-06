package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.EbookContentResponse;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.ChapterUpdateRequest;
import com.ebookwriter.SaaS.request.EbookContentUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The editing contract: content round-trips through the DB, saving a COMPLETED
 * book persists edits and re-renders the PDF uncapped, and a book that is still
 * generating cannot be edited.
 */
@ExtendWith(MockitoExtension.class)
class EbookContentServiceTest {

    @Mock EbookRepository ebookRepository;
    @Mock EbookChapterRepository chapterRepository;
    @Mock PdfGenerationService pdfGenerationService;

    @InjectMocks EbookService ebookService;

    UUID ebookId;
    UUID userId;
    Ebook ebook;

    @BeforeEach
    void setUp() {
        ebookId = UUID.randomUUID();
        userId = UUID.randomUUID();
        ebook = Ebook.builder()
                .topic("Test")
                .title("My Book")
                .subtitle("A subtitle")
                .status(EbookStatus.COMPLETED)
                .build();
    }

    private EbookChapter chapter(int number, String title, String content) {
        return EbookChapter.builder()
                .chapterNumber(number)
                .title(title)
                .content(content)
                .build();
    }

    @Test
    void getContentReturnsChaptersWithBodies() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(List.of(chapter(1, "Intro", "# Hello")));

        EbookContentResponse resp = ebookService.getContent(ebookId, userId);

        assertTrue(resp.isEditable());
        assertEquals(1, resp.getChapters().size());
        assertEquals("# Hello", resp.getChapters().get(0).getContent());
    }

    @Test
    void updateContentPersistsEditsAndRerenders() {
        EbookChapter ch1 = chapter(1, "Intro", "old body");
        EbookChapter ch2 = chapter(2, "Two", "keep me");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(List.of(ch1, ch2));

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(1, "New Title", "new body")));

        ebookService.updateContent(ebookId, userId, req);

        // Edited chapter mutated in place; the untouched one is left alone.
        assertEquals("new body", ch1.getContent());
        assertEquals("New Title", ch1.getTitle());
        assertEquals("keep me", ch2.getContent());

        verify(chapterRepository).saveAll(anyList());
        // Re-render is uncapped (maxPages = 0) so user edits are never trimmed.
        verify(pdfGenerationService).renderAndStore(eq(ebookId), eq(0));
    }

    @Test
    void updateContentIgnoresUnknownChapterNumbers() {
        EbookChapter ch1 = chapter(1, "Intro", "body");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(List.of(ch1));

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(99, "Ghost", "nope")));

        assertDoesNotThrow(() -> ebookService.updateContent(ebookId, userId, req));
        assertEquals("body", ch1.getContent()); // untouched
        verify(pdfGenerationService).renderAndStore(eq(ebookId), eq(0));
    }

    @Test
    void cannotEditWhileStillGenerating() {
        ebook.setStatus(EbookStatus.WRITING);
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(1, "x", "y")));

        assertThrows(IllegalStateException.class,
                () -> ebookService.updateContent(ebookId, userId, req));
        verify(pdfGenerationService, never()).renderAndStore(any(UUID.class), anyInt());
    }

    @Test
    void missingEbookIsNotFound() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> ebookService.getContent(ebookId, userId));
    }
}
