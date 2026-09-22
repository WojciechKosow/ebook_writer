package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The single ebook-generation brief provided by the user.
 *
 * <p>The user no longer chooses a page count. Scrivetta decides how much content
 * a complete, worthwhile ebook needs from the topic, scope and quality of the
 * material — the final length is a <em>result</em> of generation, not an order.
 * The generation budget is the user's credit balance (see the credits API), and
 * any exact page count sent by an older client is ignored.
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
}
