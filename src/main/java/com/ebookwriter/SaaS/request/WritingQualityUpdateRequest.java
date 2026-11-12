package com.ebookwriter.SaaS.request;

import com.ebookwriter.SaaS.entity.WritingQuality;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Change a draft's writing quality ({@code PUT /api/ebooks/{id}/writing-quality}). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WritingQualityUpdateRequest {

    @NotNull
    private WritingQuality writingQuality;
}
