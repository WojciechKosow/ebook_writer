package com.ebookwriter.SaaS.request;

import com.ebookwriter.SaaS.entity.BookDepth;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The single ebook-generation brief provided by the user.
 *
 * <p>The user chooses a {@link #depth} — Quick, Standard or Comprehensive — not a
 * page count. Scrivetta determines the book's length from the topic, the
 * materials and that depth; the page count is a result of generation. (A
 * {@code targetPages} sent by an older client is ignored.)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookRequest {

    @NotBlank
    private String topic;

    private String targetAudience;

    /** Optional: what the book should achieve for its reader (its goal / purpose). */
    private String bookGoal;

    private String style;

    /** e.g. "English". Defaults to English when blank. */
    private String language;

    private String additionalInstructions;

    /** Optional examples or source material to ground the book. */
    private String sourceMaterial;

    /** Optional author/creator name to print on the cover. */
    private String authorName;

    /** How deep the book should go. Optional — {@link BookDepth#STANDARD} when absent. */
    private BookDepth depth;
}
