package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout step for generated images: turns a planner's <em>semantic</em>
 * anchor (a chapter + an optional section heading) into an inline
 * {@code ![alt](ebook-image:<id>)} token in that chapter's Markdown. The
 * Markdown is the single source of truth for in-chapter position, so once the
 * token is placed the shared render path (editor preview and PDF) positions the
 * image identically — the AI never chooses pixel coordinates.
 *
 * <p>The insertion logic is pure and static so it can be unit-tested without a
 * database; the service method just applies it to a chapter entity.
 */
@Service
public class ImagePlacementService {

    /**
     * Place {@code reference} in the chapter's Markdown, after the section named
     * by {@code anchorHeading} when it can be found, otherwise after the chapter's
     * opening block. Mutates and returns the chapter (its content is updated).
     */
    public EbookChapter place(EbookChapter chapter, String reference, String anchorHeading, String altText) {
        String updated = insertReference(chapter.getContent(), reference, anchorHeading, altText);
        chapter.setContent(updated);
        return chapter;
    }

    /**
     * Insert an image token into {@code markdown} at a logical anchor and return
     * the new Markdown. If {@code anchorHeading} matches a heading in the text the
     * image is placed right after that heading's block; otherwise it is placed
     * after the first block (so it sits under the chapter's opening rather than at
     * the very top or buried at the end). A blank/absent body yields just the
     * image block.
     */
    static String insertReference(String markdown, String reference, String anchorHeading, String altText) {
        String imageBlock = "![" + sanitizeAlt(altText) + "](" + reference + ")";
        if (markdown == null || markdown.isBlank()) {
            return imageBlock;
        }

        List<String> blocks = new ArrayList<>(List.of(markdown.strip().split("\\n\\s*\\n")));

        int anchorIndex = anchorHeading == null ? -1 : findHeadingBlock(blocks, anchorHeading);
        // Fall back to after the first block when the heading isn't found or none
        // was given. With a single block this appends the image after it.
        int insertAfter = anchorIndex >= 0 ? anchorIndex : 0;

        blocks.add(insertAfter + 1, imageBlock);
        return String.join("\n\n", blocks);
    }

    /** Index of the first block that is a Markdown heading matching {@code anchor}, or -1. */
    private static int findHeadingBlock(List<String> blocks, String anchor) {
        String target = normalise(anchor);
        if (target.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < blocks.size(); i++) {
            String heading = headingText(blocks.get(i));
            if (heading == null) {
                continue;
            }
            String h = normalise(heading);
            if (h.equals(target) || h.contains(target) || target.contains(h)) {
                return i;
            }
        }
        return -1;
    }

    /** The heading text of a block whose first line is a Markdown heading, else null. */
    private static String headingText(String block) {
        String firstLine = block.strip().split("\\n", 2)[0].strip();
        if (!firstLine.startsWith("#")) {
            return null;
        }
        return firstLine.replaceFirst("^#{1,6}\\s*", "").strip();
    }

    private static String normalise(String s) {
        return s == null ? "" : s.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    /** Keep alt text on one line and free of the ']' that would break the token. */
    private static String sanitizeAlt(String alt) {
        if (alt == null || alt.isBlank()) {
            return "Illustration";
        }
        String cleaned = alt.replaceAll("[\\r\\n]+", " ").replace("]", "").strip();
        return cleaned.length() > 160 ? cleaned.substring(0, 160).strip() : cleaned;
    }
}
