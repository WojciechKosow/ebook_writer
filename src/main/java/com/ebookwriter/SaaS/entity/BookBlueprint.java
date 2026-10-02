package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The Book Blueprint of one book: the plan of the book before it is written —
 * concept, title, audience, reader goal, promise, chapters (each mapped to the
 * author's knowledge and sources) and knowledge gaps. {@link #blueprintJson} is a
 * versioned {@code com.ebookwriter.SaaS.dto.blueprint.BlueprintData}; the
 * questions asked about the gaps, and the author's answers, live in
 * {@link BlueprintQuestion} rows so they survive edits and regeneration.
 *
 * <p>The blueprint references {@link BookKnowledge} (it never copies the
 * materials): BookKnowledge stays the source of knowledge, the blueprint is the
 * map of how the book uses it. Built by OpenAI; Claude is not involved.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "book_blueprints")
public class BookBlueprint {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id", unique = true)
    private Ebook ebook;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BlueprintStatus status = BlueprintStatus.NOT_STARTED;

    @Column(columnDefinition = "text")
    private String errorMessage;

    /** The blueprint (JSON); null until the first successful build. */
    @Column(columnDefinition = "text")
    private String blueprintJson;

    private int schemaVersion;

    /** Successful + failed builds so far — capped per book. Also tags the questions of each build. */
    private int generation;

    /** BookKnowledge.processingRuns the blueprint was built from — detects knowledge re-processed since. */
    private int knowledgeRun;

    /** True once the author changed anything by hand (regeneration then needs confirmation). */
    private boolean userEdited;

    // ---- Usage / cost (last build + lifetime) ---------------------------------

    private String model;
    private int openAiCalls;
    private long inputTokens;
    private long outputTokens;
    private double estimatedCostUsd;
    private long totalInputTokens;
    private long totalOutputTokens;
    private double totalEstimatedCostUsd;

    @Column(columnDefinition = "text")
    private String warningsJson;

    private LocalDateTime startedAt;
    private LocalDateTime generatedAt;
    private LocalDateTime readyAt;
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
