package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.EbookImageDTO;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the assets (images) attached to an ebook/project: upload to R2,
 * listing, raw byte retrieval (the bucket is private, so previews are proxied
 * through the API), metadata edits (role/resize), cover selection, and deletion.
 * All operations are scoped to the owning user.
 *
 * <p>Assets persist for the life of the ebook — uploaded before generation and
 * still available in the editor afterwards. When an asset is added, removed, or
 * the cover changes on a book that has already finished generating, the PDF is
 * re-rendered (uncapped, free — like manual edits) so the download always
 * matches the book's current assets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EbookImageService {

    /** Content types we accept for ebook assets. */
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml");

    /** File extension to store per accepted content type. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif",
            "image/svg+xml", "svg");

    /** Per-image size ceiling (bytes). Bigger uploads are rejected up front. */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final EbookRepository ebookRepository;
    private final EbookImageRepository imageRepository;
    private final R2StorageService storage;
    private final PdfGenerationService pdfGenerationService;
    private final AssetUsageService assetUsageService;

    @Transactional
    public EbookImageDTO upload(UUID ebookId, UUID userId, MultipartFile file, EbookImageRole role) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        byte[] bytes = readBytes(file);

        // Trust the bytes, not the client's declared type: sniff the real format
        // and reject anything that isn't an accepted image.
        String contentType = detectContentType(bytes);
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported or unrecognised image. Allowed: PNG, JPEG, WebP, GIF, SVG.");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Image is too large (max " + (MAX_IMAGE_BYTES / (1024 * 1024)) + " MB)");
        }

        // The R2 object key just needs to be unique — keep it independent of the
        // entity id, which is DB-generated on insert. (Presetting the @GeneratedValue
        // id would make Spring Data treat the entity as existing and issue an UPDATE
        // that matches no row, failing with a stale-state error.)
        String key = objectKey(ebookId, UUID.randomUUID(), EXTENSIONS.get(contentType));
        storage.upload(key, bytes, contentType);

        int[] dimensions = readDimensions(bytes);

        EbookImage image = EbookImage.builder()
                .ebook(ebook)
                .storageKey(key)
                // Do not force a role/placement on upload — everything starts
                // general and unused; analysis or the editor assigns meaning.
                .role(role == null ? EbookImageRole.GENERAL : role)
                .placement(EbookImagePlacement.UNUSED)
                .contentType(contentType)
                .originalFilename(sanitizeFilename(file.getOriginalFilename()))
                .sizeBytes(bytes.length)
                .width(dimensions[0])
                .height(dimensions[1])
                .build();
        image = imageRepository.save(image);

        // A freshly uploaded asset is unused (not the cover, not referenced in any
        // chapter), so it cannot change the rendered PDF — no re-render needed. It
        // becomes visible in the download once it is placed via a content save,
        // set as the cover, etc., each of which re-renders.
        log.info("Uploaded asset {} for ebook {} ({}, {} KB)",
                image.getId(), ebookId, contentType, bytes.length / 1024);
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

    /**
     * Update editor-adjustable metadata: the asset's role and/or its inline
     * display width. Null fields are left unchanged. Marks the asset as
     * user-touched so a future regeneration keeps the change.
     */
    @Transactional
    public EbookImageDTO updateMetadata(UUID ebookId, UUID userId, UUID imageId,
                                        EbookImageRole role, Integer displayWidthPercent) {
        return updateMetadata(ebookId, userId, imageId, role, displayWidthPercent, null, null);
    }

    /**
     * As {@link #updateMetadata(UUID, UUID, UUID, EbookImageRole, Integer)}, also
     * setting the crop focal point (0–100% of width/height) used when the image
     * fills a region of a different shape (the cover).
     */
    @Transactional
    public EbookImageDTO updateMetadata(UUID ebookId, UUID userId, UUID imageId,
                                        EbookImageRole role, Integer displayWidthPercent,
                                        Integer focalX, Integer focalY) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        EbookImage image = imageRepository.findByIdAndEbookId(imageId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));

        boolean sizeChanged = false;
        if (role != null) {
            image.setRole(role);
        }
        if (displayWidthPercent != null) {
            int clamped = Math.max(1, Math.min(100, displayWidthPercent));
            sizeChanged = !Integer.valueOf(clamped).equals(image.getDisplayWidthPercent());
            image.setDisplayWidthPercent(clamped);
            image.setPlacedBy(ContentSource.USER);
        }
        if (focalX != null || focalY != null) {
            Integer fx = focalX == null ? image.getFocalX() : Integer.valueOf(Math.max(0, Math.min(100, focalX)));
            Integer fy = focalY == null ? image.getFocalY() : Integer.valueOf(Math.max(0, Math.min(100, focalY)));
            sizeChanged |= !java.util.Objects.equals(fx, image.getFocalX())
                    || !java.util.Objects.equals(fy, image.getFocalY());
            image.setFocalX(fx);
            image.setFocalY(fy);
        }
        image = imageRepository.save(image);

        // A resize/reframe only affects the PDF if the image is actually placed.
        if (sizeChanged && image.getPlacement() != EbookImagePlacement.UNUSED) {
            reRenderIfCompleted(ebook);
        }
        return EbookImageDTO.from(image);
    }

    /** Promote an image to the book's cover, demoting any current cover. */
    @Transactional
    public EbookImageDTO setCover(UUID ebookId, UUID userId, UUID imageId) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        EbookImage target = imageRepository.findByIdAndEbookId(imageId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));

        for (EbookImage current : imageRepository.findByEbookIdAndPlacement(ebookId, EbookImagePlacement.COVER)) {
            if (!current.getId().equals(imageId)) {
                current.setPlacement(EbookImagePlacement.UNUSED);
                current.setChapter(null);
                imageRepository.save(current);
            }
        }
        target.setPlacement(EbookImagePlacement.COVER);
        target.setChapter(null);
        target.setPlacedBy(ContentSource.USER);
        if (target.getRole() == EbookImageRole.GENERAL) {
            target.setRole(EbookImageRole.COVER);
        }
        target = imageRepository.save(target);

        reRenderIfCompleted(ebook);
        return EbookImageDTO.from(target);
    }

    /**
     * Remove the book's cover: demote any current cover asset back to UNUSED
     * (the asset itself is kept in the library, just no longer the cover) and
     * re-render so the download drops the cover image.
     */
    @Transactional
    public void clearCover(UUID ebookId, UUID userId) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        List<EbookImage> covers =
                imageRepository.findByEbookIdAndPlacement(ebookId, EbookImagePlacement.COVER);
        for (EbookImage cover : covers) {
            cover.setPlacement(EbookImagePlacement.UNUSED);
            cover.setChapter(null);
            cover.setPlacedBy(ContentSource.USER);
            imageRepository.save(cover);
        }
        if (!covers.isEmpty()) {
            reRenderIfCompleted(ebook);
        }
    }

    @Transactional
    public void delete(UUID ebookId, UUID userId, UUID imageId) {
        Ebook ebook = requireOwnedEbook(ebookId, userId);
        EbookImage image = imageRepository.findByIdAndEbookId(imageId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));

        storage.delete(image.getStorageKey());
        imageRepository.delete(image);

        // Its token may still sit in chapter Markdown; the renderer drops
        // unresolved references, so the download stays clean after re-render.
        reRenderIfCompleted(ebook);
        log.info("Deleted asset {} from ebook {}", imageId, ebookId);
    }

    // ---- internals ----------------------------------------------------------

    private Ebook requireOwnedEbook(UUID ebookId, UUID userId) {
        return ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
    }

    /**
     * Keep the downloadable PDF in sync with the book's assets. Only a COMPLETED
     * book has a rendered PDF to refresh; while generating (or still a draft),
     * the pipeline renders with whatever assets exist when it reaches the render
     * step. Usage is re-synced first so placement metadata stays accurate.
     */
    private void reRenderIfCompleted(Ebook ebook) {
        if (ebook.getStatus() == EbookStatus.COMPLETED) {
            assetUsageService.sync(ebook.getId(), ContentSource.USER);
            pdfGenerationService.renderAndStore(ebook.getId(), 0);
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No image file was provided");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read uploaded image: " + e.getMessage(), e);
        }
    }

    /**
     * Detect the image type from the file's magic bytes (never the client's
     * declared MIME). Returns the normalised content type, or null if the bytes
     * are not a supported image.
     */
    static String detectContentType(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F'
                && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (looksLikeSvg(b)) {
            return "image/svg+xml";
        }
        return null;
    }

    /** True if the leading bytes look like an SVG document. */
    private static boolean looksLikeSvg(byte[] b) {
        int len = Math.min(b.length, 1024);
        String head = new String(b, 0, len, StandardCharsets.UTF_8)
                .replace("﻿", "").stripLeading().toLowerCase();
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"));
    }

    /** Best-effort pixel dimensions; {@code [0, 0]} if they can't be read (e.g. SVG). */
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
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    /** Raw image bytes + metadata for the download endpoint. */
    public record RawImage(byte[] bytes, String contentType, String filename) {
    }
}
