package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One question Scrivetta asks the author about a knowledge gap in the
 * blueprint, and the author's answer. Stored per book (not in frontend state)
 * so the answers are available to the writing stage. Answered questions are
 * kept across blueprint regeneration; open/skipped ones are replaced.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "blueprint_questions", indexes = @Index(name = "idx_blueprint_questions_ebook", columnList = "ebook_id"))
public class BlueprintQuestion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id")
    private Ebook ebook;

    /** The blueprint gap this question resolves ({@code BlueprintData.Gap#id}), if any. */
    private String gapId;

    /** The chapter it mainly serves ({@code BlueprintData.Chapter#id}); null = whole book. */
    private String chapterId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    /** Why Scrivetta asks — shown to the author. */
    @Column(columnDefinition = "text")
    private String reason;

    /** 1 = must know … 5 = nice to have. */
    private int priority;

    /** Display order. */
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private QuestionStatus status = QuestionStatus.OPEN;

    @Column(columnDefinition = "text")
    private String answer;

    private LocalDateTime answeredAt;

    /** The blueprint build that asked it. */
    private int generation;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
