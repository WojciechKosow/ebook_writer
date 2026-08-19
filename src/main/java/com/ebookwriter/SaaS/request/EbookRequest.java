package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The single ebook-generation brief provided by the user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookRequest {

    @NotBlank
    private String topic;

    private String targetAudience;

    private String style;

    /** Desired approximate length, in pages. Used as a soft target. */
    @Min(1)
    @Max(500)
    private int approxPageCount;

    /** e.g. "English". Defaults to English when blank. */
    private String language;

    private String additionalInstructions;

    /** Optional examples or source material to ground the book. */
    private String sourceMaterial;
}
