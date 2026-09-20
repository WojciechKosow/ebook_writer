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

    /**
     * The <b>target length</b>, in pages — the expected size of the ebook, not a
     * guaranteed final page count. The AI can't hit an exact number, so the book
     * may finish a little shorter or longer. Credits are charged on the real
     * final page count (1 credit = 1 final page), not on this target.
     */
    @Min(1)
    @Max(500)
    private int approxPageCount;

    /** e.g. "English". Defaults to English when blank. */
    private String language;

    private String additionalInstructions;

    /** Optional examples or source material to ground the book. */
    private String sourceMaterial;
}
