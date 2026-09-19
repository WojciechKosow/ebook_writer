package com.ebookwriter.SaaS.dto.image;

/**
 * A single, validated decision by the Image Planner: that one image should exist
 * at a specific place in the book, what it should communicate, and the prompt to
 * generate it with. This is the "what should exist" record — distinct from the
 * generated file (an {@code EbookImage} whose bytes live in R2), which the
 * generator produces from this plan.
 *
 * <p>Placement is <em>semantic</em>, never physical: the plan names a chapter and
 * (optionally) a section heading within it to anchor the image after. The layout
 * pipeline turns that into an inline {@code ebook-image:<id>} token in the
 * chapter Markdown, and the shared render path (editor preview and PDF) decides
 * the actual on-page position — so the AI never chooses pixel coordinates.
 *
 * @param id               stable id within this plan run, e.g. {@code "img_1"}
 * @param chapterNumber    1-based chapter the image belongs to
 * @param anchorHeading    exact text of a section heading in that chapter to
 *                         place the image after; {@code null} = after the
 *                         chapter's opening block
 * @param type             the kind of visual to generate
 * @param purpose          why the image earns its place (what it clarifies)
 * @param description       what the image should depict, in plain language
 * @param generationPrompt  the prompt sent to the image model
 * @param aspectRatio      requested aspect ratio (mapped to a real size later)
 * @param priority         1 = most important; used to break ties when caps bind
 */
public record ImagePlan(
        String id,
        int chapterNumber,
        String anchorHeading,
        ImageType type,
        String purpose,
        String description,
        String generationPrompt,
        AspectRatio aspectRatio,
        int priority
) {
    /** A short alt/caption for the inline image, derived from the plan. */
    public String altText() {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        if (purpose != null && !purpose.isBlank()) {
            return purpose.trim();
        }
        return "Illustration";
    }
}
