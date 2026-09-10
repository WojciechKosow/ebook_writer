package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;

import java.util.List;

/**
 * Step 1.5 — decide how the user's uploaded assets should be used in the book:
 * cover, a specific chapter, or not at all. The model is told to be selective —
 * relevance over coverage — and to prefer a suitable user asset over leaving a
 * spot for a generated image.
 */
public final class AssetPlacementPrompts {

    private AssetPlacementPrompts() {
    }

    public static String system(String language) {
        return """
                You assign a set of user-uploaded images to the right places in a
                book. You do NOT write the book; you only decide, per image, where
                it belongs.

                For EACH image decide exactly one:
                - "cover"   — it works as the book's cover (at most ONE image total).
                - "chapter" — it belongs inside a specific chapter; give that
                              chapter's number.
                - "unused"  — it is not relevant to this book. Leaving an image
                              unused is completely fine and expected.

                Principles:
                - Relevance over coverage. Do NOT force every image in. An image
                  that doesn't clearly fit must be "unused".
                - A logo often suits the cover or an "about"/company chapter; a
                  product shot suits the chapter about that product; a chart/figure
                  suits the most data-relevant chapter; a portrait suits an author
                  or about section.
                - At most one "cover". If several could be covers, pick the best and
                  mark the rest by their real role.
                - Judge from the filename, dimensions and any description provided.

                Also classify each image's role as one of:
                general, logo, author, product, cover, illustration.
                And give a short (<=12 word) description and up to 4 lowercase tags.

                Reply with ONLY a JSON object, no prose, in this exact shape:
                {
                  "placements": [
                    {
                      "id": "<the image id given to you>",
                      "decision": "cover" | "chapter" | "unused",
                      "chapterNumber": <integer or null>,
                      "role": "general|logo|author|product|cover|illustration",
                      "description": "<short description>",
                      "tags": ["tag1", "tag2"]
                    }
                  ]
                }
                Language for descriptions/tags: %s.
                """.formatted(blankToEnglish(language));
    }

    public static String user(Ebook e, List<EbookChapter> chapters, List<EbookImage> assets) {
        StringBuilder chaptersText = new StringBuilder();
        for (EbookChapter c : chapters) {
            chaptersText.append("  ").append(c.getChapterNumber()).append(". ")
                    .append(nz(c.getTitle()));
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                chaptersText.append(" — ").append(c.getDescription().trim());
            }
            chaptersText.append("\n");
        }

        StringBuilder assetsText = new StringBuilder();
        for (EbookImage a : assets) {
            assetsText.append("  - id=").append(a.getId())
                    .append(" | filename=").append(nz(a.getOriginalFilename()))
                    .append(" | type=").append(nz(a.getContentType()));
            if (a.getWidth() > 0 && a.getHeight() > 0) {
                assetsText.append(" | dimensions=").append(a.getWidth()).append("x").append(a.getHeight());
            }
            if (a.getAiDescription() != null && !a.getAiDescription().isBlank()) {
                assetsText.append(" | description=").append(a.getAiDescription().trim());
            }
            assetsText.append("\n");
        }

        return """
                BOOK
                Title: %s
                Topic: %s
                Audience: %s
                Description: %s

                CHAPTERS
                %s
                UPLOADED IMAGES (decide a placement for each)
                %s
                Return the JSON object now.
                """.formatted(
                nz(e.getTitle()),
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getDescription()),
                chaptersText.toString().strip(),
                assetsText.toString().strip());
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
