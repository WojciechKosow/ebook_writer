package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An image belonging to an {@link Ebook}. The bytes live in the private R2
 * bucket under {@link #storageKey}; only the metadata is kept in the database.
 *
 * <p>An asset with {@link EbookImagePlacement#COVER} is rendered on the book's
 * cover page (at most one per book). An asset placed in a chapter
 * ({@link EbookImagePlacement#CHAPTER}) appears where its {@link #markdownRef()}
 * token sits in that chapter's Markdown — the render pipeline rewrites the token
 * to the stored object so the image is embedded in the PDF.
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

    /**
     * What the asset depicts. Uploads default to {@link EbookImageRole#GENERAL};
     * asset analysis or the editor may refine it.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EbookImageRole role = EbookImageRole.GENERAL;

    /** Where the asset is used in the book. Defaults to unused. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EbookImagePlacement placement = EbookImagePlacement.UNUSED;

    /**
     * The chapter this asset is placed in, when {@link #placement} is
     * {@link EbookImagePlacement#CHAPTER}. Kept in sync with the chapter Markdown
     * (which holds the authoritative in-chapter position via the image token).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private EbookChapter chapter;

    /**
     * Who decided the current placement — the AI pipeline or the user. Lets a
     * future regeneration preserve user placements while re-deciding AI ones.
     */
    @Enumerated(EnumType.STRING)
    private ContentSource placedBy;

    /**
     * Display width as a percentage of the text column (1–100) for inline
     * placements; null means natural/default sizing. Persists the editor's
     * "resize" action.
     */
    private Integer displayWidthPercent;

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

    /** AI- (or user-) supplied description of what the asset shows. */
    @Column(columnDefinition = "text")
    private String aiDescription;

    /** Comma-separated tags (kept simple; exposed as a list in the API). */
    @Column(columnDefinition = "text")
    private String tags;

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
