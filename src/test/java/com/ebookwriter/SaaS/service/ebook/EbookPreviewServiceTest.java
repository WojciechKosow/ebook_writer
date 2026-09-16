package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the browser-preview builder offline: inline {@code ebook-image:<id>}
 * references and the cover must be turned into self-contained {@code data:} URIs
 * with the persisted display width applied, and the PDF font families must be
 * mapped to their web-font equivalents. A stubbed {@link R2StorageService}
 * supplies bytes so nothing touches R2.
 */
class EbookPreviewServiceTest {

    private EbookPreviewService service(R2StorageService storage) {
        return new EbookPreviewService(null, null, null, new EbookHtmlBuilder(), storage);
    }

    @Test
    void inlinesCoverAndChapterImagesAsDataUris() {
        R2StorageService storage = mock(R2StorageService.class);
        byte[] bytes = "PNGBYTES".getBytes();
        when(storage.download(anyString())).thenReturn(Optional.of(bytes));

        Ebook ebook = Ebook.builder().topic("Illustrated").title("An Illustrated Book").build();

        UUID coverId = UUID.randomUUID();
        UUID inlineId = UUID.randomUUID();
        EbookImage cover = EbookImage.builder()
                .id(coverId).ebook(ebook)
                .role(EbookImageRole.COVER).placement(EbookImagePlacement.COVER)
                .storageKey("ebooks/x/images/" + coverId + ".png").contentType("image/png").build();
        EbookImage inline = EbookImage.builder()
                .id(inlineId).ebook(ebook)
                .role(EbookImageRole.ILLUSTRATION).placement(EbookImagePlacement.CHAPTER)
                .displayWidthPercent(60)
                .storageKey("ebooks/x/images/" + inlineId + ".png").contentType("image/png").build();

        EbookChapter chapter = EbookChapter.builder()
                .chapterNumber(1)
                .title("With a picture")
                .content("Some text.\n\n![a picture](" + inline.markdownRef() + ")\n\nMore text.")
                .build();

        String html = service(storage).buildPreviewHtml(ebook, List.of(chapter), List.of(cover, inline));

        // The raw token scheme must be fully resolved — no dangling references.
        assertFalse(html.contains(EbookImage.REF_SCHEME), "no ebook-image: tokens should remain");
        assertTrue(html.contains("data:image/png;base64,"), "images should be inlined as data URIs");
        assertTrue(html.contains("width:60%"), "the persisted display width should be applied");
        // Font families are mapped to web fonts so the browser renders faithfully.
        assertTrue(html.contains("Tinos") && html.contains("fonts.googleapis.com"),
                "PDF fonts should be mapped to web fonts");
        // Both images (cover + inline) were pulled from storage.
        verify(storage, org.mockito.Mockito.times(2)).download(anyString());
    }

    @Test
    void dropsUnresolvedImageReferences() {
        R2StorageService storage = mock(R2StorageService.class);

        Ebook ebook = Ebook.builder().topic("Broken").title("Broken Refs").build();
        EbookChapter chapter = EbookChapter.builder()
                .chapterNumber(1)
                .title("Dangling")
                .content("Text.\n\n![gone](ebook-image:" + UUID.randomUUID() + ")\n\nEnd.")
                .build();

        String html = service(storage).buildPreviewHtml(ebook, List.of(chapter), List.of());

        assertFalse(html.contains(EbookImage.REF_SCHEME), "the dangling reference should be removed");
        verify(storage, never()).download(anyString());
    }
}
