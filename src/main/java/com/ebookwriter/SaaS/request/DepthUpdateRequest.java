package com.ebookwriter.SaaS.request;

import com.ebookwriter.SaaS.entity.BookDepth;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Change a draft's depth ({@code PUT /api/ebooks/{id}/depth}). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepthUpdateRequest {

    @NotNull
    private BookDepth depth;
}
