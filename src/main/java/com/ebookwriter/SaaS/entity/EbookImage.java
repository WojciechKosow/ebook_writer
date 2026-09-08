package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An image belonging to an {@link Ebook}. The bytes live in the private R2
 * bucket under {@link #storageKey}; only the metadata is kept in the database.
 *
 * <p>A {@link EbookImageRole#COVER} image is rendered on the book's cover page
 * (at most one per book). A {@link EbookImageRole#INLINE} image is placed by the
 * author inside a chapter by referencing it in Markdown with the token returned
 * by {@link #markdownRef()} — the render pipeline rewrites that token to the
 * stored object so the image is embedded in the PDF.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ebook_images", indexes = {
        @Index(columnList = "ebook_id")
})
public class EbookImage {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id")
    private Ebook ebook;

    /** Object key in the R2 bucket (e.g. {@code ebooks/<ebookId>/images/<uuid>.png}). */
    @Column(nullable = false, columnDefinition = "text")
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EbookImageRole role = EbookImageRole.INLINE;

    /** MIME type of the stored bytes (e.g. {@code image/png}). */
    @Column(nullable = false)
    private String contentType;

    /** Original upload filename, kept for display in the editor. */
    private String originalFilename;

    /** Size of the stored image in bytes. */
    private long sizeBytes;

    /** Pixel dimensions, when they could be read from the image (else 0). */
    private int width;
    private int height;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * The Markdown/URL token the author uses to place this image inside a
     * chapter: {@code ![caption](ebook-image:<id>)}. The render pipeline resolves
     * this token to the stored object and embeds the image in the PDF.
     */
    public String markdownRef() {
        return "ebook-image:" + id;
    }

    /** URL scheme prefix for image references inside chapter Markdown. */
    public static final String REF_SCHEME = "ebook-image:";
}
