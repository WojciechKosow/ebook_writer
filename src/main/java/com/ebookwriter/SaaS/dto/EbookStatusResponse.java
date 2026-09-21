package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything the polling frontend needs: current status, progress percentage,
 * plan-derived metadata (once available), per-chapter progress, and an error
 * message on failure.
 */
@Data
@Builder
@AllArgsConstructor
public class EbookStatusResponse {

    private UUID id;
    private EbookStatus status;
    private int progress;
    private String title;
    private String subtitle;
    private String description;
    private String errorMessage;
    private boolean downloadReady;

    /**
     * The <b>final</b> number of pages in the rendered PDF, set once generation
     * completes (0 while still generating). Scrivetta decides the length from the
     * topic; this is the real result, not something the user ordered. It is also
     * what the user is billed: 1 credit = 1 final page.
     */
    private int actualPageCount;

    /**
     * Credits charged for this generation, known once complete. Equal to
     * {@link #actualPageCount} (billed on the real final length, which may be a
     * little above or below the target).
     */
    private int creditsCharged;

    private List<ChapterProgressDTO> chapters;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static EbookStatusResponse from(Ebook ebook, List<ChapterProgressDTO> chapters) {
        return EbookStatusResponse.builder()
                .id(ebook.getId())
                .status(ebook.getStatus())
                .progress(ebook.getProgress())
                .title(ebook.getTitle())
                .subtitle(ebook.getSubtitle())
                .description(ebook.getDescription())
                .errorMessage(ebook.getErrorMessage())
                .downloadReady(ebook.getStatus() == EbookStatus.COMPLETED)
                .actualPageCount(ebook.getActualPageCount())
                .creditsCharged(ebook.getCreditsCharged())
                .chapters(chapters)
                .createdAt(ebook.getCreatedAt())
                .updatedAt(ebook.getUpdatedAt())
                .build();
    }
}
