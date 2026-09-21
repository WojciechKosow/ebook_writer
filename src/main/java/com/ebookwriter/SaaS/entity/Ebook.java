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
     * real rendered page count once generation finishes — because 1 credit =
     * 1 <em>final</em> page, this is the actual page count, not the target.
     */
    private int creditsCharged;

    /**
     * The generation ceiling reserved as the up-front hold: the most pages this
     * book may render and the most credits it may cost. Computed as
     * {@code min(targetPages, balance) + maxOverdraft}, so it lets the book run a
     * little past the target (the allowed overdraft) while guaranteeing the
     * balance can never drop below {@code -maxOverdraft}. NOT the target — the
     * user's requested {@link #approxPageCount} is only the expected length.
     */
    private int pageBudget;

    /** Real number of pages in the rendered PDF; set once rendering completes. This is what the user is billed. */
    private int actualPageCount;

    /** True once those credits have been refunded (after a failure). */
    @Builder.Default
    private boolean creditsRefunded = false;

    /**
     * True once the up-front hold has been trued up to the real page count
     * (charge the actual pages, refund the unused reservation). Makes the final
     * billing idempotent: a retried or duplicated settlement is a no-op, so
     * credits are never charged twice for the same ebook.
     *
     * <p>{@code columnDefinition} carries a DB-level {@code default false} so that
     * adding this column to a table that already has ebook rows (schema auto-update
     * in production) back-fills the existing rows instead of failing the NOT NULL
     * constraint. Already-completed ebooks are thus treated as not-yet-reconciled,
     * which is harmless: they never re-enter the billing pipeline.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean creditsReconciled = false;

    // ---- Plan-derived metadata ----------------------------------------------

    private String title;
    private String subtitle;

    /**
     * Optional author/creator name shown on the cover. Nullable: most briefs
     * don't set it, and the cover simply omits the byline when it is absent.
     */
    private String authorName;

    @Column(columnDefinition = "text")
    private String description;

    /** Global writing/style guidelines produced by the planning step. */
    @Column(columnDefinition = "text")
    private String writingGuidelines;

    /**
     * The cover composition variant (how the AI visual and the typography are
     * arranged). Chosen during generation; editable afterwards. Null renders the
     * safe typographic cover, so the column is nullable and needs no default for
     * {@code ddl-auto=update} to add it to an existing table.
     */
    @Enumerated(EnumType.STRING)
    private com.ebookwriter.SaaS.dto.cover.CoverLayout coverLayout;

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
