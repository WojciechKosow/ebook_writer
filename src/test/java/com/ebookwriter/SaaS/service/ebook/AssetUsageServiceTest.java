package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/**
 * The chapter Markdown is the source of truth for inline placement. Sync must
 * place an asset in the chapter whose Markdown holds its token, mark one whose
 * token appears nowhere as unused, and never touch the cover.
 */
@ExtendWith(MockitoExtension.class)
class AssetUsageServiceTest {

    @Mock EbookChapterRepository chapterRepository;
    @Mock EbookImageRepository imageRepository;
    @InjectMocks AssetUsageService service;

    @Test
    void placesUnplacesAndLeavesCoverAlone() {
        UUID ebookId = UUID.randomUUID();

        EbookChapter ch1 = EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(1)
                .content("Intro text, no images here.").build();
        EbookImage placed = image();       // will be referenced from chapter 2
        EbookImage dangling = image();     // referenced nowhere -> UNUSED
        EbookImage cover = image();
        cover.setPlacement(EbookImagePlacement.COVER);

        EbookChapter ch2 = EbookChapter.builder().id(UUID.randomUUID()).chapterNumber(2)
                .content("Look here: " + placed.markdownRef() + " end.").build();

        when(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId))
                .thenReturn(List.of(ch1, ch2));
        when(imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId))
                .thenReturn(List.of(placed, dangling, cover));

        service.sync(ebookId, ContentSource.USER);

        assertEquals(EbookImagePlacement.CHAPTER, placed.getPlacement());
        assertSame(ch2, placed.getChapter());
        assertEquals(ContentSource.USER, placed.getPlacedBy());

        assertEquals(EbookImagePlacement.UNUSED, dangling.getPlacement());
        assertNull(dangling.getChapter());

        // Cover is managed explicitly, never derived from Markdown.
        assertEquals(EbookImagePlacement.COVER, cover.getPlacement());
    }

    private static EbookImage image() {
        return EbookImage.builder().id(UUID.randomUUID()).build();
    }
}
