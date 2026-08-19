package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.EbookChapter;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChapterProgressDTO {
    private int chapterNumber;
    private String title;
    private ChapterStatus status;

    public static ChapterProgressDTO from(EbookChapter c) {
        return new ChapterProgressDTO(c.getChapterNumber(), c.getTitle(), c.getStatus());
    }
}
