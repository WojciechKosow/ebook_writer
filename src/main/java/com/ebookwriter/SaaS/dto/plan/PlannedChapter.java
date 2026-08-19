package com.ebookwriter.SaaS.dto.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlannedChapter(
        String title,
        String description,
        int approxPages
) {
}
