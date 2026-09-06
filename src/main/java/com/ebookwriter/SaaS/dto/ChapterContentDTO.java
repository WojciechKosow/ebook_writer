package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.EbookChapter;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * A single chapter's editable body. Unlike {@link ChapterProgressDTO} (which is
 * status-only for polling), this carries the full Markdown {@code content} so
 * the frontend text editor can load and save it.
 *
 * <p>The stable {@code id} is what lets the editor add, remove and reorder
 * chapters: on save it echoes back each chapter's id (or {@code null} for a
 * newly added one) so the backend can tell edits from insertions and deletions.
 */
@Data
@AllArgsConstructor
public class ChapterContentDTO {
    private UUID id;
    private int chapterNumber;
    private String title;
    /** Chapter body in Markdown — what the user edits. */
    private String content;

    public static ChapterContentDTO from(EbookChapter c) {
        return new ChapterContentDTO(c.getId(), c.getChapterNumber(), c.getTitle(), c.getContent());
    }
}
