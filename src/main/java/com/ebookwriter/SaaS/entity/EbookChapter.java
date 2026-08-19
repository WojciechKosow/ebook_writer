package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A single chapter of an {@link Ebook}. The outline fields (title, description,
 * approxPages) come from the planning step; content and summary are filled in
 * during writing and refined during editing. Persisting each chapter as it is
 * produced is what lets a failed run keep already-written chapters.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ebook_chapters", indexes = {
        @Index(columnList = "ebook_id, chapterNumber")
})
public class EbookChapter {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id")
    private Ebook ebook;

    /** 1-based position in the book. */
    private int chapterNumber;

    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** Target size for this chapter, in pages, derived from the plan. */
    private int approxPages;

    /** Chapter body in Markdown. */
    @Column(columnDefinition = "text")
    private String content;

    /** Short summary used as context for later chapters (avoids repetition). */
    @Column(columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ChapterStatus status = ChapterStatus.PENDING;
}
