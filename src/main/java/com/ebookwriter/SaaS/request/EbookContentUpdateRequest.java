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

    /** The book's type from the Style tab; null leaves it as it is. */
    @Valid
    private Typography typography;

    /** Font keys and spacing names as the editor names them (see BookTypography). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Typography {
        @Pattern(regexp = "news|bodoni|lora|merri|playfair|bask|georgia|source|inter|mont")
        private String body;
        @Pattern(regexp = "news|bodoni|lora|merri|playfair|bask|georgia|source|inter|mont|same")
        private String heading;
        @Pattern(regexp = "compact|normal|airy")
        private String lineHeight;
        @Pattern(regexp = "tight|normal|open")
        private String paragraphSpacing;
    }

    /**
     * Rebuild the PDF as part of this save. Defaults to true; the editor's
     * autosave sends false and the PDF is rebuilt on export or download.
     */
    private Boolean render;

    public EbookContentUpdateRequest(List<ChapterUpdateRequest> chapters) {
        this.chapters = chapters;
    }
}
