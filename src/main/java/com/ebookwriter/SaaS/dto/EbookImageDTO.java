package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * An ebook asset as returned to the frontend. The bucket is private, so the
 * bytes are served through the authenticated {@code .../images/{id}/raw}
 * endpoint ({@link #rawUrl}) rather than a public URL. {@link #markdownRef} is
 * the token used to place the asset inside a chapter's Markdown.
 */
@Data
@AllArgsConstructor
public class EbookImageDTO {

    private UUID id;
    private EbookImageRole role;
    private EbookImagePlacement placement;
    /** Chapter the asset is placed in (when placement is CHAPTER), else null. */
    private UUID chapterId;
    /** Who decided the current placement (AI or USER), else null. */
    private ContentSource placedBy;
    /** Display width as a percentage of the text column (inline), or null for default. */
    private Integer displayWidthPercent;
    /** Crop focal point (percent of width/height, null = centre) for cropped placements such as the cover. */
    private Integer focalX;
    private Integer focalY;
    private String contentType;
    private String originalFilename;
    private long sizeBytes;
    private int width;
    private int height;
    private String aiDescription;
    private List<String> tags;
    /** Markdown token to place this asset in a chapter: {@code ![caption](ebook-image:<id>)}. */
    private String markdownRef;
    /** Relative API path that streams the image bytes (auth required). */
    private String rawUrl;
    private LocalDateTime createdAt;

    public static EbookImageDTO from(EbookImage image) {
        return new EbookImageDTO(
                image.getId(),
                image.getRole(),
                image.getPlacement(),
                image.getChapter() != null ? image.getChapter().getId() : null,
                image.getPlacedBy(),
                image.getDisplayWidthPercent(),
                image.getFocalX(),
                image.getFocalY(),
                image.getContentType(),
                image.getOriginalFilename(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getAiDescription(),
                splitTags(image.getTags()),
                image.markdownRef(),
                "/api/ebooks/" + image.getEbook().getId() + "/images/" + image.getId() + "/raw",
                image.getCreatedAt());
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
