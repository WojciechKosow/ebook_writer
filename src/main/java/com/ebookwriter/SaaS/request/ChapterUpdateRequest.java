package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One chapter's edited state, matched to an existing chapter by
 * {@link #chapterNumber}. {@code title} may be blank; {@code content} carries
 * the edited Markdown body (may be empty, e.g. if the user cleared a chapter).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChapterUpdateRequest {

    @Min(1)
    private int chapterNumber;

    private String title;

    @NotNull
    private String content;
}
