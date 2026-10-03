package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.BookDepth;
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

    /** The depth the user selected — the user's control over scope. */
    private BookDepth depth;

    /**
     * Scrivetta's length estimate taken when generation started (whole-book
     * pages). An estimate, never a target; null for drafts — a draft's live
     * estimate is served by {@code GET /api/ebooks/{id}/scope}.
     */
    private Integer estimatedPagesLow;
    private Integer estimatedPagesHigh;

    /** Pages of the actual plan once the book is planned (still a plan, not a promise). */
    private Integer plannedPages;

    /**
     * True when the writing ran past what the user's credits cover and the book
     * was brought to its planned ending early (see the DEFERRED chapters). Shown
     * to the user — never silent.
     */
    private boolean creditLimited;

    /** The whole-book length the user has agreed to (the estimate's high end, or a later approval). */
    private Integer approvedPages;

    /**
     * While {@code AWAITING_APPROVAL}: the length Scrivetta now expects, where the
     * pause happened ({@code PLAN} / {@code WRITING}) and how many more credits must
     * be reserved to continue at that length (0 when the hold already covers it).
     */
    private Integer proposedPages;
    private String approvalStage;
    private int extraCreditsToContinue;

    /** The user chose to keep the book within the agreed length. */
    private boolean fitToBudget;

    /**
     * The <b>final</b> number of pages in the rendered PDF, set once generation
     * completes (0 while still generating). Scrivetta decides the length from the
     * topic; this is the real result, not something the user ordered. It is also
     * what the user is billed: 1 credit = 1 final page.
     */
    private int actualPageCount;

    /**
     * Credits charged for this generation, known once complete. Equal to
     * {@link #actualPageCount} (billed on the real final length).
     */
    private int creditsCharged;

    /** LEGACY (brief-only) or KNOWLEDGE (written from the author's knowledge + blueprint). */
    private com.ebookwriter.SaaS.entity.GenerationMode generationMode;

    /** True when a failed generation kept written chapters and can be resumed. */
    private boolean resumable;

    private List<ChapterProgressDTO> chapters;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static EbookStatusResponse from(Ebook ebook, List<ChapterProgressDTO> chapters) {
        return from(ebook, chapters, false);
    }

    public static EbookStatusResponse from(Ebook ebook, List<ChapterProgressDTO> chapters, boolean resumable) {
        return EbookStatusResponse.builder()
                .id(ebook.getId())
                .status(ebook.getStatus())
                .progress(ebook.getProgress())
                .title(ebook.getTitle())
                .subtitle(ebook.getSubtitle())
                .description(ebook.getDescription())
                .errorMessage(ebook.getErrorMessage())
                .downloadReady(ebook.getStatus() == EbookStatus.COMPLETED)
                .depth(ebook.effectiveDepth())
                .estimatedPagesLow(ebook.getEstimatedPagesLow())
                .estimatedPagesHigh(ebook.getEstimatedPagesHigh())
                .plannedPages(ebook.getPlannedPages())
                .creditLimited(ebook.isCreditLimited())
                .approvedPages(ebook.getApprovedPages())
                .proposedPages(ebook.getProposedPages())
                .approvalStage(ebook.getApprovalStage())
                .extraCreditsToContinue(ebook.getStatus() == EbookStatus.AWAITING_APPROVAL
                        ? com.ebookwriter.SaaS.service.ebook.ScopeApproval.extraHold(ebook.getProposedHold(),
                        ebook.getPageBudget()) : 0)
                .fitToBudget(ebook.isFitToBudget())
                .actualPageCount(ebook.getActualPageCount())
                .creditsCharged(ebook.getCreditsCharged())
                .generationMode(ebook.getGenerationMode() == null
                        ? com.ebookwriter.SaaS.entity.GenerationMode.LEGACY : ebook.getGenerationMode())
                .resumable(resumable)
                .chapters(chapters)
                .createdAt(ebook.getCreatedAt())
                .updatedAt(ebook.getUpdatedAt())
                .build();
    }
}
