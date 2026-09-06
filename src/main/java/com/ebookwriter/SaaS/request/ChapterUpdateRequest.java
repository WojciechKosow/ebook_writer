package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One chapter in a save from the editor. {@code id} identifies an existing
 * chapter to keep/update; a {@code null} id means "create a new chapter". The
 * chapter's position in the request list becomes its new chapter number, so
 * reordering, inserting and appending all fall out of the list order. Existing
 * chapters whose id is absent from the save are deleted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChapterUpdateRequest {

    /** Existing chapter id, or null to add a new chapter. */
    private UUID id;

    private String title;

    @NotNull
    private String content;
}
