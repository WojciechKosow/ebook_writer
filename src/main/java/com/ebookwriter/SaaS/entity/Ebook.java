package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One ebook generation request and its evolving result. Holds the original
 * user brief, the generation status/progress, the plan-derived metadata
 * (title, subtitle, description, writing guidelines) and — once rendered — the
 * final PDF bytes. Chapters are stored in {@link EbookChapter} so partial
 * progress survives a failure.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ebooks")
public class Ebook {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    // ---- Original request ---------------------------------------------------

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    @Column(columnDefinition = "text")
    private String targetAudience;

    @Column(columnDefinition = "text")
    private String style;

    private int approxPageCount;

    private String language;

    @Column(columnDefinition = "text")
    private String additionalInstructions;

    @Column(columnDefinition = "text")
    private String sourceMaterial;

    // ---- Generation state ---------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EbookStatus status = EbookStatus.PENDING;

    @Builder.Default
    private int progress = 0;

    @Column(columnDefinition = "text")
    private String errorMessage;

    /**
     * Credits the user ultimately pays for this generation. Starts equal to the
     * reserved {@link #pageBudget} (the up-front hold) and is trued up to the
     * real rendered page count once generation finishes.
     */
    private int creditsCharged;

    /**
     * Page ceiling reserved for this generation (1 credit = 1 page): the most
     * pages we will produce and the most credits we may charge. Generation is
     * capped to this so we never spend on more pages than the user authorised.
     */
    private int pageBudget;

    /** Real number of pages in the rendered PDF; set once rendering completes. */
    private int actualPageCount;

    /** True once those credits have been refunded (after a failure). */
    @Builder.Default
    private boolean creditsRefunded = false;

    // ---- Plan-derived metadata ----------------------------------------------

    private String title;
    private String subtitle;

    @Column(columnDefinition = "text")
    private String description;

    /** Global writing/style guidelines produced by the planning step. */
    @Column(columnDefinition = "text")
    private String writingGuidelines;

    /** Raw plan JSON kept for debugging / potential reprocessing. */
    @Column(columnDefinition = "text")
    private String planJson;

    // Rendered PDF bytes live in a separate table (EbookPdf) so that status
    // polls and listings don't load the blob.

    // ---- Chapters -----------------------------------------------------------

    @OneToMany(mappedBy = "ebook", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("chapterNumber ASC")
    @Builder.Default
    private List<EbookChapter> chapters = new ArrayList<>();

    // ---- Timestamps ---------------------------------------------------------

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
