package com.ebookwriter.SaaS.request;

import com.ebookwriter.SaaS.entity.EbookImageRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Editor-adjustable asset metadata. Both fields are optional; a null field is
 * left unchanged. {@code displayWidthPercent} persists a "resize" (1–100% of the
 * text column) for an inline image.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookImageUpdateRequest {

    private EbookImageRole role;

    @Min(1)
    @Max(100)
    private Integer displayWidthPercent;
}
