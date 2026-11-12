package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Step 1.5 — decide how the author's uploaded images are used in the book:
 * cover, a specific chapter (and where in it), or not at all. The model LOOKS at
 * the images it is shown (a photographer's portraits, a trainer's exercise
 * photos, a creator's screenshots…), reads the author's own note on each, and
 * says what each image shows that matters for this book — so the writer can
 * place it and refer to it from what it really shows, not from its file name.
 */
public final class AssetPlacementPrompts {

    private AssetPlacementPrompts() {
    }

    public static String system(String language) {
        return """
                You place the author's own images in their book. You do NOT write the book; you
                decide, per image, where it belongs and you describe what it shows. The images
                you can see are attached after the list; an image without an attachment can only
                be judged from its note, file name and dimensions.

                THE AUTHOR'S NOTE COMES FIRST
                - Many images have a note from the author ("the window-light example for
                  chapter 2", "my client doing a goblet squat — use it in the technique part").
                  Follow it: place the image where the note says, unless that chapter does not
                  exist. Use the note's wording for what the image is meant to show.

                For EACH image decide exactly one:
                - "cover"   — it works as the book's cover (at most ONE image in total).
                - "chapter" — it belongs inside a specific chapter; give that chapter's number
                              and, in "section", the topic or step of that chapter it illustrates.
                - "unused"  — it is not relevant to this book. Leaving an image unused is fine.

                Principles:
                - Relevance over coverage. Do NOT force every image in.
                - Match an image to the chapter whose subject it actually SHOWS (a side-lit
                  portrait → the side-lighting chapter; a deadlift setup → the deadlift
                  technique; a timeline screenshot → the editing-workflow chapter).
                - A logo suits the cover or an "about" section; a portrait of the author suits
                  an author/about section.

                Describe what you SEE, factually and specifically, as it matters for this book:
                - "analysis": 2–4 sentences — the subject, and what the image demonstrates for
                  this book's topic (for a photo: direction and quality of the light, shadows,
                  background, framing, depth of field, expression or pose; for a fitness photo:
                  the exercise, the position, what is done right or wrong; for a screenshot:
                  the tool and what is on screen). Do not guess camera settings or facts you
                  cannot see, and never identify a real person by name unless the note says who
                  it is.
                - "description": at most 12 words, a caption-like summary.

                Also classify each image's role as one of:
                general, logo, author, product, cover, illustration.

                Reply with ONLY a JSON object, no prose, in this exact shape:
                {
                  "placements": [
                    {
                      "id": "<the image id given to you>",
                      "decision": "cover" | "chapter" | "unused",
                      "chapterNumber": <integer or null>,
                      "section": "<the topic/step of that chapter it illustrates, or null>",
                      "role": "general|logo|author|product|cover|illustration",
                      "description": "<short description>",
                      "analysis": "<what the image shows and demonstrates>",
                      "tags": ["tag1", "tag2"]
                    }
                  ]
                }
                Language for descriptions, analyses and tags: %s.
                """.formatted(blankToEnglish(language));
    }

    /**
     * The book, its chapters and the images to place, with the author's note on
     * each and whether the image itself is attached for the model to see.
     */
    public static String user(Ebook e, List<EbookChapter> chapters, List<EbookImage> assets, Set<UUID> attached) {
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
            assetsText.append(attached.contains(a.getId()) ? " | attached below" : " | not attached (judge from the note)");
            if (a.getAuthorNote() != null && !a.getAuthorNote().isBlank()) {
                assetsText.append("\n      AUTHOR'S NOTE: ").append(a.getAuthorNote().strip().replace("\n", " "));
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
                THE AUTHOR'S IMAGES (decide a placement for each)
                %s
                """.formatted(
                nz(e.getTitle()),
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getDescription()),
                chaptersText.toString().strip(),
                assetsText.toString().strip());
    }

    /** Closes the request after the attached images. */
    public static final String OUTRO = "Return the JSON object now.";

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
