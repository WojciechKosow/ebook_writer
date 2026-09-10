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
import org.mockito.ArgumentCaptor;
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
 * The editing contract: content round-trips through the DB; a save is the
 * authoritative chapter list, so it covers editing existing chapters (by id),
 * adding new ones (null id), removing omitted ones, and reordering by list
 * position; and a book that is still generating cannot be edited.
 */
@ExtendWith(MockitoExtension.class)
class EbookContentServiceTest {

    @Mock EbookRepository ebookRepository;
    @Mock EbookChapterRepository chapterRepository;
    @Mock PdfGenerationService pdfGenerationService;
    @Mock AssetUsageService assetUsageService;

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

    private EbookChapter chapter(UUID id, int number, String title, String content) {
        return EbookChapter.builder()
                .id(id)
                .chapterNumber(number)
                .title(title)
                .content(content)
                .build();
    }

    /** saveAll echoes its argument, mirroring JPA returning the saved entities. */
    private void echoSaveAll() {
        when(chapterRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void getContentReturnsChaptersWithIdsAndBodies() {
        UUID cid = UUID.randomUUID();
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(List.of(chapter(cid, 1, "Intro", "# Hello")));

        EbookContentResponse resp = ebookService.getContent(ebookId, userId);

        assertTrue(resp.isEditable());
        assertEquals(1, resp.getChapters().size());
        assertEquals(cid, resp.getChapters().get(0).getId());
        assertEquals("# Hello", resp.getChapters().get(0).getContent());
    }

    @Test
    void editsExistingChapterById() {
        UUID id1 = UUID.randomUUID();
        EbookChapter ch1 = chapter(id1, 1, "Intro", "old body");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(new java.util.ArrayList<>(List.of(ch1)));
        echoSaveAll();

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(id1, "New Title", "new body")));

        ebookService.updateContent(ebookId, userId, req);

        assertEquals("new body", ch1.getContent());
        assertEquals("New Title", ch1.getTitle());
        verify(chapterRepository, never()).deleteAll(anyList());
        // Re-render is uncapped (maxPages = 0) so user edits are never trimmed.
        verify(pdfGenerationService).renderAndStore(eq(ebookId), eq(0));
    }

    @Test
    void addsNewChapterForNullId() {
        UUID id1 = UUID.randomUUID();
        EbookChapter ch1 = chapter(id1, 1, "Intro", "body one");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(new java.util.ArrayList<>(List.of(ch1)));
        echoSaveAll();

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(id1, "Intro", "body one"),
                new ChapterUpdateRequest(null, "Fresh", "brand new")));

        ebookService.updateContent(ebookId, userId, req);

        ArgumentCaptor<List<EbookChapter>> captor = ArgumentCaptor.forClass(List.class);
        verify(chapterRepository).saveAll(captor.capture());
        List<EbookChapter> saved = captor.getValue();

        assertEquals(2, saved.size());
        assertEquals(1, saved.get(0).getChapterNumber());
        assertEquals(2, saved.get(1).getChapterNumber());
        assertEquals("Fresh", saved.get(1).getTitle());
        assertEquals("brand new", saved.get(1).getContent());
        assertSame(ebook, saved.get(1).getEbook()); // new chapter linked to the book
    }

    @Test
    void removesChaptersOmittedFromTheSave() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        EbookChapter ch1 = chapter(id1, 1, "Keep", "keep body");
        EbookChapter ch2 = chapter(id2, 2, "Drop", "drop body");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(new java.util.ArrayList<>(List.of(ch1, ch2)));
        echoSaveAll();

        // Only ch1 survives the save.
        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(id1, "Keep", "keep body")));

        ebookService.updateContent(ebookId, userId, req);

        ArgumentCaptor<List<EbookChapter>> deleted = ArgumentCaptor.forClass(List.class);
        verify(chapterRepository).deleteAll(deleted.capture());
        assertEquals(List.of(ch2), deleted.getValue());
    }

    @Test
    void reordersByListPosition() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        EbookChapter ch1 = chapter(id1, 1, "First", "a");
        EbookChapter ch2 = chapter(id2, 2, "Second", "b");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(new java.util.ArrayList<>(List.of(ch1, ch2)));
        echoSaveAll();

        // Swap the order.
        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(id2, "Second", "b"),
                new ChapterUpdateRequest(id1, "First", "a")));

        ebookService.updateContent(ebookId, userId, req);

        assertEquals(1, ch2.getChapterNumber());
        assertEquals(2, ch1.getChapterNumber());
    }

    @Test
    void rejectsUnknownChapterId() {
        UUID id1 = UUID.randomUUID();
        EbookChapter ch1 = chapter(id1, 1, "Intro", "body");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(new java.util.ArrayList<>(List.of(ch1)));

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(UUID.randomUUID(), "Ghost", "nope")));

        assertThrows(IllegalArgumentException.class,
                () -> ebookService.updateContent(ebookId, userId, req));
        verify(pdfGenerationService, never()).renderAndStore(any(UUID.class), anyInt());
    }

    @Test
    void cannotEditWhileStillGenerating() {
        ebook.setStatus(EbookStatus.WRITING);
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));

        EbookContentUpdateRequest req = new EbookContentUpdateRequest(List.of(
                new ChapterUpdateRequest(null, "x", "y")));

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
