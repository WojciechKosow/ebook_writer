package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An ebook image as returned to the frontend. The bucket is private, so the
 * bytes are served through the authenticated {@code .../images/{id}/raw}
 * endpoint ({@link #rawUrl}) rather than a public URL. For inline images,
 * {@link #markdownRef} is the token the author pastes into a chapter to place
 * the image.
 */
@Data
@AllArgsConstructor
public class EbookImageDTO {

    private UUID id;
    private EbookImageRole role;
    private String contentType;
    private String originalFilename;
    private long sizeBytes;
    private int width;
    private int height;
    /** Markdown token to place this image in a chapter: {@code ![caption](ebook-image:<id>)}. */
    private String markdownRef;
    /** Relative API path that streams the image bytes (auth required). */
    private String rawUrl;
    private LocalDateTime createdAt;

    public static EbookImageDTO from(EbookImage image) {
        return new EbookImageDTO(
                image.getId(),
                image.getRole(),
                image.getContentType(),
                image.getOriginalFilename(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.markdownRef(),
                "/api/ebooks/" + image.getEbook().getId() + "/images/" + image.getId() + "/raw",
                image.getCreatedAt());
    }
}
