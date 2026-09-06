package com.ebookwriter.SaaS.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A save from the text editor: the edited chapters to persist. Each entry is
 * matched to an existing chapter by its number; unknown numbers are ignored.
 * Chapters omitted from the list are left untouched, so the editor may save a
 * subset.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookContentUpdateRequest {

    @NotEmpty
    @Valid
    private List<ChapterUpdateRequest> chapters;
}
