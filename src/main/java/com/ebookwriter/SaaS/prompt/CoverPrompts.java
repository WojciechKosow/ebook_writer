package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.List;

/**
 * Cover art-direction prompts. The model acts as an art director: it analyses the
 * book (subject, audience, tone) and returns, as strict JSON, a chosen cover
 * layout and a <b>text-free</b> image prompt for the visual only. Scrivetta —
 * not the image model — renders the title, subtitle, author and imprint, so the
 * image prompt must never ask for words, logos or typography, and must leave
 * negative space where the layout places the title.
 */
public final class CoverPrompts {

    private CoverPrompts() {
    }

    /** How much of each chapter title list to show the art director. */
    private static final int MAX_CHAPTERS = 12;

    /**
     * Non-negotiable constraints appended to <em>every</em> image prompt before it
     * reaches the image model — a hard guarantee that the generated asset carries
     * no text or branding, whatever the plan said. Kept separate so it can be
     * enforced in code even for a fallback prompt.
     */
    public static final String IMAGE_CONSTRAINTS =
            " Premium editorial cover art. Minimal, restrained, intelligent, sophisticated. "
                    + "A single clear focal point with generous negative space and calm composition. "
                    + "Absolutely NO text, NO words, NO letters, NO numbers, NO typography, NO title, "
                    + "NO logos, NO watermark, NO UI, NO screenshots, NO book or device mockups, "
                    + "NO frames or borders. Not a stock photo, not clip art, not a collage.";

    public static String system() {
        return """
                You are an art director designing the cover of a non-fiction ebook.
                You do NOT write or set any text. You only decide the cover's visual
                direction and composition. Scrivetta renders the title, subtitle,
                author and imprint as real typography on top of your visual, so your
                image must contain NO words of any kind.

                Analyse the book — subject, audience, emotional tone — and decide:
                1. The most fitting COMPOSITION (choose exactly one):
                   - EDITORIAL   : a large visual in the upper area, strong title below
                                   it on paper; title safe area is the LOWER third.
                   - IMAGE_LED   : the visual fills the whole cover; title sits over a
                                   scrim; title safe area is the BOTTOM band.
                   - SPLIT       : visual on the top ~55%, typography on a panel below;
                                   title safe area is the BOTTOM panel.
                   - MINIMAL     : a small, quiet visual with lots of negative space and
                                   strong typography; title safe area is the UPPER area.
                   - TYPOGRAPHIC : no image is needed; the subject reads better as pure
                                   typography. Use this only when a visual would add
                                   nothing.
                2. The visual DIRECTION: photography, conceptual illustration, abstract
                   or textural — whatever genuinely suits this subject and audience.
                3. A concrete IMAGE PROMPT for an image model that renders ONLY the
                   visual: concrete subject, composition, palette and mood. It must
                   leave clear, uncluttered negative space in the layout's title safe
                   area so the typography stays readable. Support the meaning of the
                   book, never merely decorate.

                Reply with ONLY a JSON object, no prose, in exactly this shape:
                {
                  "layout": "EDITORIAL|IMAGE_LED|SPLIT|MINIMAL|TYPOGRAPHIC",
                  "visualConcept": "<one plain sentence describing the visual>",
                  "imagePrompt": "<prompt for the image model, no text/words/logos>",
                  "aspectRatio": "2:3|3:2|1:1"
                }
                If TYPOGRAPHIC, set imagePrompt to "" and aspectRatio to "2:3".
                Never include the title, subtitle, author, brand or any lettering in
                the imagePrompt.
                """;
    }

    public static String user(Ebook e, List<EbookChapter> chapters) {
        StringBuilder concepts = new StringBuilder();
        int shown = 0;
        for (EbookChapter c : chapters) {
            if (c.getTitle() == null || c.getTitle().isBlank()) {
                continue;
            }
            if (shown++ >= MAX_CHAPTERS) {
                break;
            }
            concepts.append("- ").append(c.getTitle().trim()).append('\n');
        }
        return """
                BOOK
                Title: %s
                Subtitle: %s
                Topic: %s
                Audience: %s
                Style/tone: %s
                Description: %s

                CHAPTER CONCEPTS
                %s
                Design the cover now. Return only the JSON object.
                """.formatted(
                nz(e.getTitle()),
                nz(e.getSubtitle()),
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                nz(e.getDescription()),
                concepts.length() == 0 ? "(none)" : concepts.toString().strip());
    }

    /**
     * A deterministic, topic-aware image prompt used when the art-director model
     * is unavailable or fails — so a cover visual can still be generated. Derives
     * a concrete concept from the book's own words (never a generic "nice image").
     */
    public static String fallbackImagePrompt(Ebook e) {
        String subject = firstNonBlank(e.getTopic(), e.getTitle(), "the book's core idea");
        String audience = nzOrNull(e.getTargetAudience());
        String tone = nzOrNull(e.getStyle());
        StringBuilder sb = new StringBuilder();
        sb.append("A sophisticated conceptual editorial cover visual about ")
                .append(subject.strip()).append('.');
        if (audience != null) {
            sb.append(" It should resonate with ").append(audience.strip()).append('.');
        }
        if (tone != null) {
            sb.append(" Visual tone: ").append(tone.strip()).append('.');
        }
        sb.append(" Use a restrained, cohesive palette and a strong single focal point,");
        sb.append(" with clean negative space in the lower third for a title.");
        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s.trim();
    }

    private static String nzOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
