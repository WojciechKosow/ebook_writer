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
                - Images: only if you are given "IMAGES AVAILABLE FOR THIS CHAPTER"
                  below, you MAY place one where it genuinely fits by writing its
                  exact token on its own line as: ![short caption](ebook-image:<id>)
                  using the id given. Use an image only if it clearly belongs; it is
                  fine to use none. Never invent image ids, filenames, or URLs, and
                  never add an image when none are offered.

                Design components (use SPARINGLY, only where they genuinely fit):
                The book has a design system. Where a piece of content is one of the
                kinds below, wrap it in a fenced ::: block so it renders as a proper
                designed component instead of a plain paragraph. These are the ONLY
                block names; write the name in lower case. Do NOT force them — most
                content is ordinary prose, and over-using components looks cluttered.
                A typical chapter uses only a few, chosen because the content is
                genuinely that kind of thing.

                  :::key-idea            one crucial insight, stated plainly
                  :::takeaway            a short summary of a section
                  :::pullquote           one memorable sentence, quoted for emphasis
                  :::warning             a common mistake or caution
                  :::example             a concrete worked example
                  :::exercise Title      a practical task for the reader
                  :::done-when           a completion criterion (or just write a
                                         paragraph starting "Done when:")
                  :::checklist Title     a list of things to verify (one "- " per line)
                  :::steps Day 1         an ordered action plan. One "- " per step,
                                         "Title | duration" then an indented
                                         description line, e.g.:
                                         - Turn off notifications | 15 min
                                           Silence everything that is not a person.
                  :::flow                a process / cycle / sequence, ONE node per
                                         line — rendered as a real diagram, so prefer
                                         this over describing a flow in prose or asking
                                         for an image. e.g.:
                                         Difficult work
                                         Discomfort
                                         Check phone
                                         Relief

                Close every block with a line containing only ::: — for example:
                :::key-idea
                Focus is a state your environment creates, not a trait you are born with.
                :::

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
                              int targetWords,
                              String availableImages,
                              int totalChapters) {
        String imagesSection = (availableImages == null || availableImages.isBlank())
                ? ""
                : "\nIMAGES AVAILABLE FOR THIS CHAPTER (use where they fit, or not at all)\n"
                        + availableImages.strip() + "\n";
        boolean isFinalChapter = totalChapters > 0 && chapter.getChapterNumber() >= totalChapters;
        String positionSection = isFinalChapter
                ? """

                        POSITION IN THE BOOK
                        This is the FINAL chapter (chapter %d of %d) — the book ends here.
                        Bring the book to a natural, satisfying close: tie together the key
                        ideas the earlier chapters established and leave the reader with a
                        clear sense of completion. Do NOT open large new topics or promise
                        further chapters. If a short recap of the main takeaways fits the
                        book's style, include it, then end the book.
                        """.formatted(chapter.getChapterNumber(), totalChapters)
                : """

                        POSITION IN THE BOOK
                        This is chapter %d of %d. Cover this chapter's scope fully, but do
                        not try to conclude the whole book — later chapters continue it.
                        """.formatted(chapter.getChapterNumber(), Math.max(totalChapters, chapter.getChapterNumber()));
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
                %s%s
                CHAPTER TO WRITE NOW
                Chapter %d: %s
                Scope: %s
                If this chapter's scope covers more than one promised unit (e.g.
                several days, steps or stages), cover every one of them — do not stop
                partway or leave the last ones as a stub.
                Target length: about %d words. Treat this as a firm limit — write to
                roughly this length and do NOT substantially exceed it. Staying a
                little under is fine; going well over is not. Respecting the length
                keeps the finished book within its generation budget.

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
                imagesSection,
                positionSection,
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
