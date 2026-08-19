package com.ebookwriter.SaaS.dto.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured plan returned by the planning step (parsed from the model's JSON).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookPlan(
        String title,
        String subtitle,
        String targetAudience,
        String description,
        String writingGuidelines,
        List<PlannedChapter> chapters
) {
}
