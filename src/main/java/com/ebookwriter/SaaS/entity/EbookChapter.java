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

    /**
     * INTERNAL: the planner's brief for this chapter (what it covers and why).
     * Written for the model, never for the reader — the renderer never sees it
     * (see {@code ReaderView}).
     */
    @Column(columnDefinition = "text")
    private String description;

    /**
     * READER-FACING: a short subtitle printed under the chapter title, generated
     * separately at its final length (never cut down from the brief). Null when
     * none was generated; nothing is printed then.
     */
    @Column(columnDefinition = "text")
    private String readerSubtitle;

    /** Target size for this chapter, in pages, derived from the plan. */
    private int approxPages;

    /** Chapter body in Markdown. */
    @Column(columnDefinition = "text")
    private String content;

    /** Short summary used as context for later chapters (avoids repetition). */
    @Column(columnDefinition = "text")
    private String summary;

    /**
     * INTERNAL: the topic registry entries this chapter contributed — one line
     * per concept, term, procedure or warning it explained ("topic — gist").
     * Later chapters get every earlier chapter's entries so they refer back
     * instead of explaining again (see {@code TopicRegistry}).
     */
    @Column(columnDefinition = "text")
    private String coveredTopics;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ChapterStatus status = ChapterStatus.PENDING;

    /**
     * Knowledge-based books: the {@code BlueprintData.Chapter#id} this chapter was
     * planned from — the link to the chapter's knowledge references, source refs,
     * key points, gaps and the author's answers. Null for legacy books.
     */
    private String blueprintChapterId;

    /** Why the last attempt to write this chapter failed (cleared once written). */
    @Column(columnDefinition = "text")
    private String generationError;

    /**
     * Whether this chapter's current content was produced by the AI pipeline or
     * edited by the user. Set to {@link ContentSource#USER} when the editor saves
     * changes, so a future regeneration can preserve manual edits.
     *
     * <p>The column carries a {@code 'AI'} default so that {@code ddl-auto=update}
     * can add it to an existing, populated {@code ebook_chapters} table: without a
     * default, adding a NOT NULL column to a table with rows fails and the column
     * is silently skipped. The default also backfills pre-existing chapters as
     * AI-authored, which is correct.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(255) default 'AI' not null")
    @Builder.Default
    private ContentSource contentSource = ContentSource.AI;
}
