package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * The full editable manuscript delivered to the text editor: book metadata plus
 * every chapter's Markdown body. {@code editable} is true only once generation
 * has finished — a book that is still being written must not be edited from
 * under the pipeline.
 */
@Data
@Builder
@AllArgsConstructor
public class EbookContentResponse {

    private UUID id;
    private EbookStatus status;
    private String title;
    private String subtitle;
    private boolean editable;
    private List<ChapterContentDTO> chapters;

    public static EbookContentResponse from(Ebook ebook, List<EbookChapter> chapters) {
        return EbookContentResponse.builder()
                .id(ebook.getId())
                .status(ebook.getStatus())
                .title(ebook.getTitle())
                .subtitle(ebook.getSubtitle())
                .editable(ebook.getStatus() == EbookStatus.COMPLETED)
                .chapters(chapters.stream().map(ChapterContentDTO::from).toList())
                .build();
    }
}
