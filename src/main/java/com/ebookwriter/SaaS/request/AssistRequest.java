package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One request to the editor's writing assistant: what to do, to which piece of
 * the page, with the chapter around it as context. Nothing is saved — the
 * suggestion goes back to the editor, where the author accepts or discards it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssistRequest {

    @NotNull
    @Pattern(regexp = "improve|pro|simple|expand|explain|examples|caption|alt|structure|takeaway")
    private String action;

    /** What the action works on: a paragraph or callout, a photo, or the whole chapter. */
    @NotNull
    @Pattern(regexp = "block|fig|ch")
    private String target;

    /** The selected text (a paragraph, or a photo's current caption). */
    @Size(max = 8000)
    private String text;

    @Size(max = 300)
    private String chapterTitle;

    /** The chapter's text, for context. */
    @Size(max = 24000)
    private String chapterText;

    /** The chapter's section headings, in order. */
    @Size(max = 60)
    private List<@Size(max = 300) String> headings;

    /** For a photo: Scrivetta's description of what it shows. */
    @Size(max = 2000)
    private String assetDescription;
}
