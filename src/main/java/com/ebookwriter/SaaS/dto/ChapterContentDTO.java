package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.EbookChapter;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A single chapter's editable body. Unlike {@link ChapterProgressDTO} (which is
 * status-only for polling), this carries the full Markdown {@code content} so
 * the frontend text editor can load and save it.
 */
@Data
@AllArgsConstructor
public class ChapterContentDTO {
    private int chapterNumber;
    private String title;
    /** Chapter body in Markdown — what the user edits. */
    private String content;

    public static ChapterContentDTO from(EbookChapter c) {
        return new ChapterContentDTO(c.getChapterNumber(), c.getTitle(), c.getContent());
    }
}
