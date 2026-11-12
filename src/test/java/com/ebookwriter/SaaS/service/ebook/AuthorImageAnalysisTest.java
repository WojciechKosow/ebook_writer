package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.ai.ModelChoice;
import com.ebookwriter.SaaS.service.ai.VisionImage;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A photographer uploads img.png (a woman in window light) with a note on where
 * it belongs. The premium model LOOKS at the image, follows the note, says what
 * the image shows — and the chapter writer receives all of it.
 */
class AuthorImageAnalysisTest {

    private final UUID ebookId = UUID.randomUUID();
    private AnthropicService ai;
    private R2StorageService storage;
    private EbookImageRepository images;
    private AssetPlacementService placement;
    private Ebook ebook;
    private EbookChapter light;
    private EbookChapter settings;

    @BeforeEach
    void setUp() throws Exception {
        ai = mock(AnthropicService.class);
        storage = mock(R2StorageService.class);
        images = mock(EbookImageRepository.class);
        EbookRepository ebooks = mock(EbookRepository.class);
        EbookChapterRepository chapters = mock(EbookChapterRepository.class);
        ebook = Ebook.builder().id(ebookId).topic("Portraits in natural light").language("English").build();
        light = EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(1).title("Window and Side Light").build();
        settings = EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(2).title("Camera Settings").build();
        when(ebooks.findById(ebookId)).thenReturn(Optional.of(ebook));
        when(chapters.findByEbookIdOrderByChapterNumberAsc(ebookId)).thenReturn(List.of(light, settings));
        when(images.save(any(EbookImage.class))).thenAnswer(i -> i.getArgument(0));
        when(storage.isConfigured()).thenReturn(true);
        placement = new AssetPlacementService(ai, ebooks, chapters, images, storage, new AnthropicProperties());
    }

    private EbookImage image(String filename, String note) {
        return EbookImage.builder().id(UUID.randomUUID()).ebook(ebook).storageKey("k/" + filename)
                .contentType("image/png").originalFilename(filename).width(3000).height(2000)
                .authorNote(note).placement(EbookImagePlacement.UNUSED).role(EbookImageRole.GENERAL).build();
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void thePremiumModelLooksAtThePhotoFollowsTheNoteAndSaysWhatItShows() throws Exception {
        EbookImage photo = image("img.png", "Side-light example — chapter 1, next to the window setup");
        when(images.findByEbookIdOrderByCreatedAtAsc(ebookId)).thenReturn(List.of(photo));
        when(storage.download("k/img.png")).thenReturn(Optional.of(png(3000, 2000)));
        when(ai.completeWithImages(anyString(), anyString(), any(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenReturn("""
                        {"placements": [{"id": "%s", "decision": "chapter", "chapterNumber": 1,
                          "section": "Side lighting", "role": "illustration",
                          "description": "Woman lit by a window from the left",
                          "analysis": "Soft window light from camera left; the right side of her face falls into gentle shadow.",
                          "tags": ["window light", "side light"]}]}
                        """.formatted(photo.getId()));

        placement.plan(ebookId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VisionImage>> sent = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeWithImages(anyString(), prompt.capture(), sent.capture(), anyString(), anyLong(),
                eq(ModelChoice.of(new AnthropicProperties().getModel())));
        assertEquals(1, sent.getValue().size(), "the photo itself was attached");
        VisionImage seen = sent.getValue().get(0);
        assertEquals(photo.getId().toString(), seen.label());
        BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(seen.bytes()));
        assertEquals(VisionImage.MAX_EDGE, Math.max(scaled.getWidth(), scaled.getHeight()), "downscaled for the model");
        assertTrue(prompt.getValue().contains("AUTHOR'S NOTE: Side-light example — chapter 1"), "the note is in the request");

        assertEquals(EbookImagePlacement.CHAPTER, photo.getPlacement());
        assertEquals(light, photo.getChapter());
        assertEquals(ContentSource.AI, photo.getPlacedBy());
        assertTrue(photo.getAiAnalysis().contains("Soft window light from camera left"));
        assertTrue(photo.getAiAnalysis().contains("Best placed with: Side lighting"));

        // The chapter writer gets the note and what the photo shows.
        String forWriter = ChapterGenerationService.describeForWriter(photo);
        assertTrue(forWriter.contains("ebook-image:" + photo.getId()));
        assertTrue(forWriter.contains("The author's note (follow it): Side-light example"));
        assertTrue(forWriter.contains("What the image shows: Soft window light from camera left"));
    }

    @Test
    void aCoverTheAuthorChoseStaysTheCover() throws Exception {
        EbookImage cover = image("cover.png", null);
        cover.setPlacement(EbookImagePlacement.COVER);
        cover.setPlacedBy(ContentSource.USER);
        EbookImage other = image("other.png", null);
        when(images.findByEbookIdOrderByCreatedAtAsc(ebookId)).thenReturn(List.of(cover, other));
        when(storage.download(anyString())).thenReturn(Optional.of(png(400, 300)));
        when(ai.completeWithImages(anyString(), anyString(), any(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenReturn("""
                        {"placements": [
                          {"id": "%s", "decision": "unused", "description": "A book cover", "analysis": "Dark title art."},
                          {"id": "%s", "decision": "cover", "description": "Portrait", "analysis": "A portrait."}]}
                        """.formatted(cover.getId(), other.getId()));

        placement.plan(ebookId);

        assertEquals(EbookImagePlacement.COVER, cover.getPlacement(), "the author's choice is kept");
        assertEquals("Dark title art.", cover.getAiAnalysis(), "...and still described");
        assertEquals(EbookImagePlacement.UNUSED, other.getPlacement(), "only one cover");
    }

    @Test
    void withoutStorageImagesArePlacedFromTheirNotes() {
        EbookImage photo = image("img.png", "Squat setup for chapter 2");
        when(images.findByEbookIdOrderByCreatedAtAsc(ebookId)).thenReturn(List.of(photo));
        when(storage.isConfigured()).thenReturn(false);
        when(ai.complete(anyString(), anyString(), anyLong())).thenReturn("""
                {"placements": [{"id": "%s", "decision": "chapter", "chapterNumber": 2, "description": "Squat setup"}]}
                """.formatted(photo.getId()));

        placement.plan(ebookId);

        verify(ai, never()).completeWithImages(anyString(), anyString(), any(), anyString(), anyLong(), any());
        assertEquals(settings, photo.getChapter());
        assertNull(photo.getAiAnalysis());
    }

    @Test
    void svgAndUnreadableFilesAreNotSentToTheModel() throws Exception {
        assertTrue(VisionImage.prepare("a", "<svg/>".getBytes(), "image/svg+xml").isEmpty());
        assertTrue(VisionImage.prepare("b", new byte[]{1, 2, 3}, "image/png").isEmpty());
        VisionImage small = VisionImage.prepare("c", png(300, 200), "image/png").orElseThrow();
        assertEquals("image/jpeg", small.mediaType());
        assertEquals(300, ImageIO.read(new ByteArrayInputStream(small.bytes())).getWidth(), "never upscaled");
    }
}
