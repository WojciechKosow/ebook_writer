package com.ebookwriter.SaaS.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The author's pasted notes. Blank text removes the notes source. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeNotesRequest {
    private String text;
}
