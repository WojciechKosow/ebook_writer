package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.EbookImageDTO;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the images attached to an ebook: upload to R2, listing, raw byte
 * retrieval (the bucket is private, so previews are proxied through the API),
 * cover selection, and deletion. All operations are scoped to the owning user.
 *
 * <p>When an image is added, removed, or the cover changes on a book that has
 * already finished generating, the PDF is re-rendered (uncapped, free — like
 * manual edits) so the download always matches the book's current images.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EbookImageService {

    /** Content types we accept for ebook images. */
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    /** File extension to store per accepted content type. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    /** Per-image size ceiling (bytes). Bigger uploads are rejected up front. */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final EbookRepository ebookRepository;
    private final EbookImageRepository imageRepository;
    private final R2StorageService storage;
    private final PdfGenerationService pdfGenerationService;

    @Transactional
    public EbookImageDTO upload(UUID ebookId, UUID userId, MultipartFile file, EbookImageRole role) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        validate(file);

        String contentType = normalizeContentType(file.getContentType());
        byte[] bytes = readBytes(file);
        EbookImageRole effectiveRole = role == null ? EbookImageRole.INLINE : role;

        // A book has at most one cover: replace any existing one.
        if (effectiveRole == EbookImageRole.COVER) {
            removeExistingCover(ebookId);
        }

        UUID imageId = UUID.randomUUID();
        String key = objectKey(ebookId, imageId, EXTENSIONS.get(contentType));
        storage.upload(key, bytes, contentType);

        int[] dimensions = readDimensions(bytes);

        EbookImage image = EbookImage.builder()
                .id(imageId)
                .ebook(ebook)
                .storageKey(key)
                .role(effectiveRole)
                .contentType(contentType)
                .originalFilename(sanitizeFilename(file.getOriginalFilename()))
                .sizeBytes(bytes.length)
                .width(dimensions[0])
                .height(dimensions[1])
                .build();
        image = imageRepository.save(image);

        reRenderIfCompleted(ebook);
        log.info("Uploaded {} image {} for ebook {} ({} KB)",
                effectiveRole, imageId, ebookId, bytes.length / 1024);
        return EbookImageDTO.from(image);
    }

    @Transactional(readOnly = true)
    public List<EbookImageDTO> list(UUID ebookId, UUID userId) {
        requireOwnedEbook(ebookId, userId);
        return imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId).stream()
                .map(EbookImageDTO::from)
                .toList();
    }

    /** Load an image's raw bytes for preview/download (bucket is private). */
    @Transactional(readOnly = true)
    public RawImage getRaw(UUID ebookId, UUID userId, UUID imageId) {
        requireOwnedEbook(ebookId, userId);
        EbookImage image = imageRepository.findByIdAndEbookId(imageId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));
        byte[] bytes = storage.download(image.getStorageKey())
                .orElseThrow(() -> new IllegalStateException("Image bytes are missing from storage"));
        return new RawImage(bytes, image.getContentType(), image.getOriginalFilename());
    }

    /** Promote an image to the book's cover, demoting any current cover to inline. */
    @Transactional
    public EbookImageDTO setCover(UUID ebookId, UUID userId, UUID imageId) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        EbookImage target = imageRepository.findByIdAndEbookId(imageId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));

        for (EbookImage current : imageRepository.findByEbookIdAndRole(ebookId, EbookImageRole.COVER)) {
            if (!current.getId().equals(imageId)) {
                current.setRole(EbookImageRole.INLINE);
                imageRepository.save(current);
            }
        }
        target.setRole(EbookImageRole.COVER);
        target = imageRepository.save(target);

        reRenderIfCompleted(ebook);
        return EbookImageDTO.from(target);
    }

    @Transactional
    public void delete(UUID ebookId, UUID userId, UUID imageId) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        EbookImage image = imageRepository.findByIdAndEbookId(imageId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));

        storage.delete(image.getStorageKey());
        imageRepository.delete(image);

        reRenderIfCompleted(ebook);
        log.info("Deleted image {} from ebook {}", imageId, ebookId);
    }

    // ---- internals ----------------------------------------------------------

    private Ebook requireOwnedEbook(UUID ebookId, UUID userId) {
        return ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
    }

    private void removeExistingCover(UUID ebookId) {
        for (EbookImage existing : imageRepository.findByEbookIdAndRole(ebookId, EbookImageRole.COVER)) {
            storage.delete(existing.getStorageKey());
            imageRepository.delete(existing);
        }
    }

    /**
     * Keep the downloadable PDF in sync with the book's images. Only a COMPLETED
     * book has a rendered PDF to refresh; while generating, the pipeline renders
     * with whatever images exist at that point.
     */
    private void reRenderIfCompleted(Ebook ebook) {
        if (ebook.getStatus() == EbookStatus.COMPLETED) {
            pdfGenerationService.renderAndStore(ebook.getId(), 0);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No image file was provided");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Image is too large (max " + (MAX_IMAGE_BYTES / (1024 * 1024)) + " MB)");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported image type: " + file.getContentType()
                            + ". Allowed: PNG, JPEG, WebP, GIF.");
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        String ct = contentType.trim().toLowerCase();
        // Treat image/jpg as the standard image/jpeg.
        return ct.equals("image/jpg") ? "image/jpeg" : ct;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read uploaded image: " + e.getMessage(), e);
        }
    }

    /** Best-effort pixel dimensions; {@code [0, 0]} if they can't be read. */
    private static int[] readDimensions(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                return new int[]{img.getWidth(), img.getHeight()};
            }
        } catch (IOException e) {
            log.debug("Could not read image dimensions: {}", e.getMessage());
        }
        return new int[]{0, 0};
    }

    private static String objectKey(UUID ebookId, UUID imageId, String ext) {
        return "ebooks/" + ebookId + "/images/" + imageId + "." + ext;
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // Keep only the base name; strip any path the browser may include.
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    /** Raw image bytes + metadata for the download endpoint. */
    public record RawImage(byte[] bytes, String contentType, String filename) {
    }
}
