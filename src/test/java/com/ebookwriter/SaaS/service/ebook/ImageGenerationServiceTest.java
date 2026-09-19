package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.image.AspectRatio;
import com.ebookwriter.SaaS.dto.image.ImagePlan;
import com.ebookwriter.SaaS.dto.image.ImageType;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.image.ImageGenerationException;
import com.ebookwriter.SaaS.service.image.OpenAiImageClient;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The generator turns plans into stored {@link EbookImage}s and inline tokens,
 * and — crucially — isolates per-image failures so one bad image never loses the
 * others or the book.
 */
class ImageGenerationServiceTest {

    private final OpenAiImageClient client = mock(OpenAiImageClient.class);
    private final R2StorageService storage = mock(R2StorageService.class);
    private final EbookRepository ebookRepository = mock(EbookRepository.class);
    private final EbookChapterRepository chapterRepository = mock(EbookChapterRepository.class);
    private final EbookImageRepository imageRepository = mock(EbookImageRepository.class);

    private ImageGenerationService service(OpenAiProperties props) {
        return new ImageGenerationService(client, props, storage, ebookRepository,
                chapterRepository, imageRepository, new ImagePlacementService());
    }

    @Test
    void storesGeneratedImageAndPlacesTokenAtAnchor() {
        UUID ebookId = setUpBook();
        EbookChapter ch1 = chapter(1, "## Basics\n\nText about money.");
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId)).thenReturn(List.of(ch1));
        stubSaveAssigningId();
        when(client.generate(anyString(), any(AspectRatio.class)))
                .thenReturn(new OpenAiImageClient.GeneratedImage(new byte[]{1, 2, 3}, "image/png"));

        ImagePlan plan = new ImagePlan("img_1", 1, "Basics", ImageType.DIAGRAM,
                "clarify", "a diagram", "draw a diagram", AspectRatio.LANDSCAPE, 1);

        int made = service(configured()).generate(ebookId, List.of(plan));

        assertEquals(1, made);

        ArgumentCaptor<EbookImage> imageCaptor = ArgumentCaptor.forClass(EbookImage.class);
        verify(imageRepository).save(imageCaptor.capture());
        EbookImage saved = imageCaptor.getValue();
        assertEquals(EbookImagePlacement.CHAPTER, saved.getPlacement());
        assertSame(ch1, saved.getChapter());
        assertEquals(ContentSource.AI, saved.getPlacedBy());
        assertEquals(EbookImageRole.ILLUSTRATION, saved.getRole());
        assertTrue(saved.getStorageKey().contains(ebookId.toString()));

        // The inline token was written into the chapter Markdown (source of truth
        // for both editor and PDF), anchored under the matched heading.
        ArgumentCaptor<EbookChapter> chapterCaptor = ArgumentCaptor.forClass(EbookChapter.class);
        verify(chapterRepository).save(chapterCaptor.capture());
        String content = chapterCaptor.getValue().getContent();
        assertTrue(content.contains(saved.markdownRef()), content);
        assertTrue(content.indexOf(saved.markdownRef()) > content.indexOf("## Basics"));

        verify(storage).upload(anyString(), any(), eq("image/png"));
    }

    @Test
    void continuesWhenOneImageFails() {
        UUID ebookId = setUpBook();
        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId)).thenReturn(List.of(
                chapter(1, "Chapter one body."),
                chapter(2, "Chapter two body."),
                chapter(3, "Chapter three body.")));
        stubSaveAssigningId();

        OpenAiImageClient.GeneratedImage ok =
                new OpenAiImageClient.GeneratedImage(new byte[]{9}, "image/png");
        // Second call blows up; the first and third still succeed.
        when(client.generate(anyString(), any(AspectRatio.class)))
                .thenReturn(ok)
                .thenThrow(new ImageGenerationException("OpenAI failed for image 2"))
                .thenReturn(ok);

        List<ImagePlan> plans = List.of(
                plan("img_1", 1), plan("img_2", 2), plan("img_3", 3));

        int made = service(configured()).generate(ebookId, plans);

        assertEquals(2, made, "one failure must not lose the other two images");
        verify(imageRepository, times(2)).save(any());
        verify(chapterRepository, times(2)).save(any());
        verify(storage, times(2)).upload(anyString(), any(), anyString());
    }

    @Test
    void skipsWhenOpenAiNotConfigured() {
        OpenAiProperties props = new OpenAiProperties();
        props.setEnabled(true);
        props.setApiKey(""); // not configured
        int made = service(props).generate(UUID.randomUUID(), List.of(plan("img_1", 1)));
        assertEquals(0, made);
        verify(imageRepository, times(0)).save(any());
    }

    @Test
    void emptyPlanIsANoOp() {
        assertEquals(0, service(configured()).generate(UUID.randomUUID(), List.of()));
    }

    // ---- helpers ------------------------------------------------------------

    private UUID setUpBook() {
        UUID ebookId = UUID.randomUUID();
        Ebook ebook = Ebook.builder().id(ebookId).topic("Finance").build();
        when(ebookRepository.findById(ebookId)).thenReturn(Optional.of(ebook));
        when(storage.isConfigured()).thenReturn(true);
        return ebookId;
    }

    private void stubSaveAssigningId() {
        when(imageRepository.save(any(EbookImage.class))).thenAnswer(inv -> {
            EbookImage img = inv.getArgument(0);
            if (img.getId() == null) {
                img.setId(UUID.randomUUID());
            }
            return img;
        });
    }

    private static OpenAiProperties configured() {
        OpenAiProperties p = new OpenAiProperties();
        p.setEnabled(true);
        p.setApiKey("test-key");
        return p;
    }

    private static EbookChapter chapter(int number, String content) {
        return EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(number).content(content).build();
    }

    private static ImagePlan plan(String id, int chapterNumber) {
        return new ImagePlan(id, chapterNumber, null, ImageType.ILLUSTRATION,
                "purpose", "description", "a prompt", AspectRatio.LANDSCAPE, 1);
    }
}
