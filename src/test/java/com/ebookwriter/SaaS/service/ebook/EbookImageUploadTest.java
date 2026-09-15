package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upload must INSERT a new asset row. {@link EbookImage#getId()} is
 * {@code @GeneratedValue}, so the service must not preset it — presetting makes
 * Spring Data treat the entity as existing and issue an UPDATE that matches no
 * row ("Row was already updated or deleted by another transaction"). This locks
 * the entity handed to {@code save} as new (null id).
 */
@ExtendWith(MockitoExtension.class)
class EbookImageUploadTest {

    @Mock EbookRepository ebookRepository;
    @Mock EbookImageRepository imageRepository;
    @Mock R2StorageService storage;
    @Mock PdfGenerationService pdfGenerationService;
    @Mock AssetUsageService assetUsageService;

    @InjectMocks EbookImageService service;

    @Test
    void uploadPersistsANewAssetWithoutPresettingTheId() throws IOException {
        UUID ebookId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Ebook ebook = Ebook.builder().id(ebookId).status(EbookStatus.DRAFT).build();
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(ebook));
        when(imageRepository.save(any(EbookImage.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", tinyPng());

        service.upload(ebookId, userId, file, null);

        ArgumentCaptor<EbookImage> captor = ArgumentCaptor.forClass(EbookImage.class);
        verify(imageRepository).save(captor.capture());
        EbookImage saved = captor.getValue();

        assertNull(saved.getId(), "id must be left for the DB to generate (insert, not merge)");
        assertEquals("image/png", saved.getContentType());
        assertEquals(EbookImageRole.GENERAL, saved.getRole());
        assertEquals(EbookImagePlacement.UNUSED, saved.getPlacement());
        assertTrue(saved.getStorageKey().startsWith("ebooks/" + ebookId + "/images/"),
                "object key should be scoped under the ebook");
        // The bytes were pushed to storage under that key.
        verify(storage).upload(anyString(), any(), anyString());
    }

    private static byte[] tinyPng() throws IOException {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ImageIO.write(img, "png", os);
        return os.toByteArray();
    }
}
