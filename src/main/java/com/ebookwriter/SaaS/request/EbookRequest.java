package com.ebookwriter.SaaS.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The single ebook-generation brief provided by the user.
 *
 * <p>The user may select a <b>target length</b> ({@link #targetPages}, e.g. ~20,
 * ~30, ~50, ~75 or ~100 pages). It is a <em>soft content budget</em>: it shapes
 * the outline (chapter count, depth, exercises, visuals) so the book naturally
 * lands around that size, but it is never a hard page limit — a book is not
 * truncated for exceeding it, nor padded to reach it. How far a book may run is
 * bounded only by the user's credits (see the credits API).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookRequest {

    @NotBlank
    private String topic;

    private String targetAudience;

    private String style;

    /** e.g. "English". Defaults to English when blank. */
    private String language;

    private String additionalInstructions;

    /** Optional examples or source material to ground the book. */
    private String sourceMaterial;

    /** Optional author/creator name to print on the cover. */
    private String authorName;

    /**
     * The selected target length in pages (soft). Optional — when absent the
     * standard target is used. Out-of-range values are clamped, not rejected.
     * {@code approxPageCount} is accepted as an alias for older clients.
     */
    @JsonAlias("approxPageCount")
    private Integer targetPages;
}
