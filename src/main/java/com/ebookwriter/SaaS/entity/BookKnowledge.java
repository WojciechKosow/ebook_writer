package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The structured knowledge Scrivetta learned from an author's materials — one
 * row per book. {@link #knowledgeJson} is a versioned
 * {@code com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData} document (topics,
 * processes, examples, insights, gaps, …, each with source references); the
 * columns around it carry the processing status and the usage numbers needed to
 * account for what the OpenAI processing cost.
 *
 * <p>Produced by OpenAI only — Claude never reads raw materials. The next stage
 * (Book Blueprint) reads it via {@code BookKnowledgeService#getBookKnowledge}.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "book_knowledge")
public class BookKnowledge {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id", unique = true)
    private Ebook ebook;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KnowledgeStatus status = KnowledgeStatus.CREATED;

    @Column(columnDefinition = "text")
    private String errorMessage;

    /** The structured knowledge (JSON); null until the first successful run. */
    @Column(columnDefinition = "text")
    private String knowledgeJson;

    private int schemaVersion;

    // ---- Usage / cost accounting (last run) ---------------------------------

    /** OpenAI model that produced {@link #knowledgeJson}. */
    private String model;

    private int openAiCalls;
    private long inputTokens;
    private long outputTokens;

    /** Estimated USD cost of the last run (configured per-token prices). */
    private double estimatedCostUsd;

    /** Characters of material sent to OpenAI in the last run. */
    private long analyzedChars;

    private int sourcesAnalyzed;
    private int documentsAnalyzed;
    /** Documents left out of the analysis because the run's size budget was reached. */
    private int documentsNotAnalyzed;
    private int duplicatesSkipped;
    private int chunks;

    /** Runs started so far — capped per book. */
    private int processingRuns;

    /** Lifetime totals across all runs, for cost reporting. */
    private long totalInputTokens;
    private long totalOutputTokens;
    private double totalEstimatedCostUsd;

    /** JSON array of human-readable warnings from the last run (skipped batches, budget, …). */
    @Column(columnDefinition = "text")
    private String warningsJson;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
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
