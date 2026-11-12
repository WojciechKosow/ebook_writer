package com.ebookwriter.SaaS.request;

import com.ebookwriter.SaaS.entity.EbookImageRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Editor-adjustable asset metadata. Every field is optional; a null field is
 * left unchanged. {@code displayWidthPercent} persists a "resize" (1–100% of the
 * text column) for an inline image; {@code focalX}/{@code focalY} (0–100% of the
 * image) set the point a cropped placement — the cover — is framed around.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookImageUpdateRequest {

    private EbookImageRole role;

    @Min(1)
    @Max(100)
    private Integer displayWidthPercent;

    @Min(0)
    @Max(100)
    private Integer focalX;

    @Min(0)
    @Max(100)
    private Integer focalY;

    /**
     * The author's note: what the image shows and where it belongs in the book.
     * Null leaves it unchanged; blank clears it.
     */
    @jakarta.validation.constraints.Size(max = MAX_AUTHOR_NOTE)
    private String authorNote;

    /** Longest author note (chars). */
    public static final int MAX_AUTHOR_NOTE = 2000;
}
