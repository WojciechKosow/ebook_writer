package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

/**
 * Step 2 — chapter writing. Chapters are generated sequentially so each one is
 * aware of what earlier chapters already covered.
 */
public final class ChapterPrompts {

    private ChapterPrompts() {
    }

    /** Delimiter separating the chapter body from its short summary. */
    public static final String SUMMARY_DELIMITER = "===SUMMARY===";

    public static String system(String language) {
        return PromptGuidelines.core(language) + """

                You are writing ONE chapter of a larger book. Write only this chapter.

                Formatting (Markdown):
                - Do NOT repeat the chapter number or the chapter title as a heading;
                  the book renders those automatically. Begin directly with the
                  chapter's content.
                - Use "##" for major section headings and "###" for subsections.
                - Use paragraphs, bulleted or numbered lists, and short bold lead-ins
                  where they genuinely help readability.
                - For code, use fenced blocks with a language tag (e.g. ```java) when
                  the topic is technical. Keep code correct and runnable.
                - Do not include images.

                After the chapter body, output the delimiter line exactly:
                %s
                then a 2-3 sentence summary of what this chapter established, written
                for the author's own reference (it will guide later chapters and will
                NOT be printed in the book).
                """.formatted(SUMMARY_DELIMITER);
    }

    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String previousSummaries,
                              int targetWords) {
        return """
                BOOK BRIEF
                Topic: %s
                Audience: %s
                Style: %s
                Language: %s
                Additional instructions: %s

                GLOBAL WRITING GUIDELINES
                %s

                FULL BOOK OUTLINE (for context — do not rewrite other chapters)
                %s

                WHAT EARLIER CHAPTERS ALREADY COVERED (do not repeat these; build on them)
                %s

                CHAPTER TO WRITE NOW
                Chapter %d: %s
                Scope: %s
                Aim for approximately %d words.

                Write this chapter now.
                """.formatted(
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                blankToEnglish(e.getLanguage()),
                nz(e.getAdditionalInstructions()),
                nz(e.getWritingGuidelines()),
                nz(fullOutline),
                previousSummaries == null || previousSummaries.isBlank()
                        ? "(this is the first chapter)" : previousSummaries.trim(),
                chapter.getChapterNumber(),
                nz(chapter.getTitle()),
                nz(chapter.getDescription()),
                targetWords
        );
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
