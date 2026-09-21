package com.ebookwriter.SaaS.dto.cover;

import com.ebookwriter.SaaS.dto.image.AspectRatio;

/**
 * The plan for a book's cover: the chosen {@link CoverLayout}, a short
 * human-readable description of the visual concept, and the <b>text-free</b>
 * prompt to send to the image model. This is <em>what the cover should be</em> —
 * the actual generated file is a separate {@code EbookImage} asset, and the
 * title/subtitle stay real text. Planning data is never baked into the image.
 *
 * @param layout        how the cover is composed
 * @param visualConcept a plain-language summary of the intended visual (for logs/UI)
 * @param imagePrompt   the prompt for the image model — contains no title, subtitle,
 *                      author, logo or any typography, and reserves negative space
 *                      for the layout's title safe area
 * @param aspectRatio   the aspect ratio to request for the visual
 */
public record CoverPlan(CoverLayout layout, String visualConcept, String imagePrompt,
                        AspectRatio aspectRatio) {

    /** Whether this plan actually calls for an image (a typographic cover may not). */
    public boolean hasVisual() {
        return layout != null && layout.requiresVisual()
                && imagePrompt != null && !imagePrompt.isBlank();
    }
}
