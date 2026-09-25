package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.List;

/**
 * Image-planning step — decide, per book, where AI-generated images genuinely
 * add value, and produce a structured plan for each. The planner does NOT write
 * the book and does NOT generate images; it only decides what visual content is
 * needed and where, as strict JSON.
 *
 * <p>The rules are deliberately conservative (relevance over coverage) so a book
 * is not littered with decorative or near-duplicate images.
 */
public final class ImagePlanningPrompts {

    private ImagePlanningPrompts() {
    }

    /** How much of each chapter's body to show the planner (chars). */
    private static final int EXCERPT_CHARS = 1500;

    public static String system(String language, int maxPerBook, int maxPerChapter) {
        return """
                You are an art director planning the illustrations for a non-fiction
                ebook. The manuscript is already written; you do NOT write or edit it,
                and you do NOT generate images. You only decide which images should be
                created, where they belong, and how each should be generated.

                Decide selectively. Most sections need NO image. Only propose an image
                when a visual genuinely explains, clarifies, or reinforces the content
                at that point.

                RULES:
                - Prefer images that explain or clarify (an illustration of a concept,
                  a scene that grounds an idea) over decorative ones.
                - DIAGRAMS ARE NOT YOUR JOB. The book renders processes, cycles,
                  sequences, timelines, comparisons, checklists and step-by-step action
                  plans with its OWN typographic diagram components — crisp text, no
                  spelling mistakes, on-brand. So do NOT propose an image for anything
                  that is mostly boxes-and-arrows, labelled steps, or text in a chart.
                  Only propose an image model image when the content genuinely needs a
                  rendered picture: an ILLUSTRATION of a concept, or a PHOTO-style scene.
                  Avoid the DIAGRAM and CHART types unless a picture truly cannot be
                  expressed as text (it almost always can).
                - Do NOT add an image just because a chapter exists. A chapter may have
                  zero images. Whole books of pure prose need no images at all.
                - Never propose decorative images that carry no information.
                - Avoid repetition: no two images should communicate nearly the same
                  thing, and do not repeat a visual motif across chapters.
                - Consider the surrounding text; anchor each image to the specific
                  section it supports.
                - Keep the whole book visually consistent (shared style, palette, tone).
                - Respect the book's language and audience in what the image shows and
                  in any embedded labels.
                - Write each generationPrompt so an image model can render it directly:
                  concrete subject, composition, and a consistent visual style. Do not
                  reference the book, the reader, or these instructions in the prompt.
                - At most %d images for the whole book, and at most %d per chapter.
                  Fewer is better. Rank by importance with "priority" (1 = most
                  important) so the best images survive if the limit binds.

                For each image, choose:
                - "type": one of ILLUSTRATION, DIAGRAM, CHART, PHOTO.
                - "aspectRatio": one of "1:1", "3:2", "2:3" (square, landscape, portrait).
                - "chapterNumber": the chapter it belongs to.
                - "anchorHeading": the exact text of a section heading in that chapter to
                  place the image AFTER, or null to place it near the chapter's start.

                Reply with ONLY a JSON object, no prose, in this exact shape:
                {
                  "images": [
                    {
                      "chapterNumber": <integer>,
                      "anchorHeading": "<heading text or null>",
                      "type": "ILLUSTRATION|DIAGRAM|CHART|PHOTO",
                      "purpose": "<why this image earns its place>",
                      "description": "<what the image depicts, plain language>",
                      "generationPrompt": "<prompt for the image model>",
                      "aspectRatio": "1:1|3:2|2:3",
                      "priority": <integer, 1 = most important>
                    }
                  ]
                }
                If the book needs no images, return {"images": []}.
                Language for descriptions and any text in images: %s.
                """.formatted(maxPerBook, maxPerChapter, blankToEnglish(language));
    }

    public static String user(Ebook e, List<EbookChapter> chapters) {
        StringBuilder chaptersText = new StringBuilder();
        for (EbookChapter c : chapters) {
            if (c.getContent() == null || c.getContent().isBlank()) {
                continue;
            }
            chaptersText.append("=== Chapter ").append(c.getChapterNumber()).append(": ")
                    .append(nz(c.getTitle())).append(" ===\n");
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                chaptersText.append("Summary: ").append(c.getDescription().trim()).append("\n");
            }
            chaptersText.append(excerpt(c.getContent())).append("\n\n");
        }

        return """
                BOOK
                Title: %s
                Topic: %s
                Audience: %s
                Style: %s
                Language: %s
                Description: %s

                CHAPTERS (with content excerpts)
                %s
                Produce the image plan JSON now.
                """.formatted(
                nz(e.getTitle()),
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                blankToEnglish(e.getLanguage()),
                nz(e.getDescription()),
                chaptersText.toString().strip());
    }

    /** A bounded excerpt of a chapter body so the whole plan prompt stays small. */
    private static String excerpt(String content) {
        String trimmed = content.strip();
        if (trimmed.length() <= EXCERPT_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, EXCERPT_CHARS) + "…";
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
