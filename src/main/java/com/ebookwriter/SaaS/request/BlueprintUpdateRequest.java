package com.ebookwriter.SaaS.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The author's edits to a blueprint. Every field is optional: null leaves it as
 * is. {@code chapters}, when present, is the authoritative list — its order is
 * the new order; an entry with an existing {@code id} edits that chapter (its
 * knowledge mapping is kept), an entry without an id adds a chapter, and a
 * chapter missing from the list is removed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlueprintUpdateRequest {
    private String workingTitle;
    private String subtitle;
    private String concept;
    private String audience;
    private String readerGoal;
    private String promise;
    private List<ChapterEdit> chapters;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChapterEdit {
        private String id;
        private String title;
        private String purpose;
        private List<String> topics;
    }
}
