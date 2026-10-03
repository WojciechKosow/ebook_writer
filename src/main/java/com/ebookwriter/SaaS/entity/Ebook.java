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

    /**
     * What the book should achieve for its reader ("teach beginners to build the
     * project from scratch"). Part of the knowledge-based brief; nullable so older
     * briefs (and {@code ddl-auto=update}) are unaffected.
     */
    @Column(columnDefinition = "text")
    private String bookGoal;

    @Column(columnDefinition = "text")
    private String style;

    /**
     * Legacy column: the page count older clients asked for. The page count is no
     * longer an input to generation (see {@link #depth}); the column is kept only
     * because existing databases have it as {@code NOT NULL}. Always 0 for new books.
     */
    @Deprecated
    private int approxPageCount;

    /**
     * How deep the book should go — the user's control over scope. Scrivetta
     * derives the length from the depth plus the topic and materials; the page
     * count is a result, never an input. Null (older books) means
     * {@link BookDepth#STANDARD}.
     */
    @Enumerated(EnumType.STRING)
    private BookDepth depth;

    private String language;

    @Column(columnDefinition = "text")
    private String additionalInstructions;

    @Column(columnDefinition = "text")
    private String sourceMaterial;

    // ---- Generation state ---------------------------------------------------

    /**
     * Which generation flow produced this book. Null = {@link GenerationMode#LEGACY}
     * (every book created before the knowledge-based flow), so the column needs no
     * default for {@code ddl-auto=update}.
     */
    @Enumerated(EnumType.STRING)
    private GenerationMode generationMode;

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
     * 1 <em>final</em> page, this is the actual page count, not the estimate.
     */
    private int creditsCharged;

    /**
     * The generation ceiling reserved as the up-front hold: the most pages this
     * book may render and the most credits it may cost. Computed as
     * {@code min(balance, maxGenerationBudget) + maxOverdraft}: a safety ceiling
     * on what the user can pay for, guaranteeing the balance can never drop below
     * {@code -maxOverdraft}. Never a length target — the book is as long as its
     * content.
     */
    private int pageBudget;

    /**
     * Scrivetta's length estimate (whole-book pages) taken when generation
     * started — what the user was shown and what the start was gated on. An
     * estimate, never a target: generation follows the content. Null until started.
     */
    private Integer estimatedPagesLow;

    /** High end of {@link #estimatedPagesLow}'s range. */
    private Integer estimatedPagesHigh;

    /**
     * Whole-book pages of the actual plan (the chapters' planned sizes plus front
     * matter), set once the book is planned. Still a plan, not a promise: the
     * real length is {@link #actualPageCount}. Null until planned.
     */
    private Integer plannedPages;

    /**
     * The whole-book length (pages) the user has agreed to: the estimate's high end
     * at start, raised when they approve a longer book. Generation pauses for the
     * user's decision when the plan or the writing goes clearly past it.
     */
    private Integer approvedPages;

    /** While {@link EbookStatus#AWAITING_APPROVAL}: the length Scrivetta now expects. */
    private Integer proposedPages;

    /** While awaiting approval: the credit hold (pages) continuing at that length needs. */
    private Integer proposedHold;

    /** While awaiting approval: {@code PLAN} (after planning) or {@code WRITING} (during writing). */
    @Column(length = 16)
    private String approvalStage;

    /**
     * The user chose to keep the book within the agreed length / their credits:
     * remaining chapters may be tightened or the book wound down to its ending
     * instead of pausing again.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean fitToBudget = false;

    /** Cached AI scope assessment (JSON) and the fingerprint of the inputs it was made from. */
    @Column(columnDefinition = "text")
    private String scopeAssessmentJson;

    @Column(length = 64)
    private String scopeAssessmentKey;

    /**
     * True when the book ran past what the user's credits cover and was brought
     * to its planned ending early (later chapters deferred). Surfaced to the user;
     * never silent. The start gate makes this rare: it only happens when the
     * writing ran far beyond the estimate.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean creditLimited = false;

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

    /**
     * Layout decision made by the renderer: when the book's last page would hold
     * only a line or two that spilled over, the final chapter is set slightly
     * tighter so that content reflows onto the previous page. Stored (not
     * recomputed) so the editor preview and the PDF use the identical layout.
     * Nullable so {@code ddl-auto=update} can add it to an existing table.
     */
    private Boolean layoutSnugEnding;

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

    /** The selected depth ({@link BookDepth#STANDARD} for books that predate depth). */
    public BookDepth effectiveDepth() {
        return BookDepth.orDefault(depth);
    }

    /** True when this book is written from the author's knowledge + blueprint. */
    public boolean isKnowledgeBased() {
        return generationMode == GenerationMode.KNOWLEDGE;
    }

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
