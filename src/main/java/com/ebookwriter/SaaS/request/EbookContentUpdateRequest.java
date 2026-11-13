package com.ebookwriter.SaaS.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A save from the editor: the chapters (the authoritative list — see
 * {@link ChapterUpdateRequest}: chapters absent from it are deleted) plus any
 * book details edited alongside them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbookContentUpdateRequest {

    @NotEmpty
    @Valid
    private List<ChapterUpdateRequest> chapters;

    /** Book details edited in the editor; a null field is left unchanged. */
    @Size(max = 255)
    private String title;
    @Size(max = 255)
    private String subtitle;
    @Size(max = 255)
    private String authorName;
    @Pattern(regexp = "editorial|framed|type")
    private String coverLayout;

    /**
     * Rebuild the PDF as part of this save. Defaults to true; the editor's
     * autosave sends false and the PDF is rebuilt on export or download.
     */
    private Boolean render;

    public EbookContentUpdateRequest(List<ChapterUpdateRequest> chapters) {
        this.chapters = chapters;
    }
}
