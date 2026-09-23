package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.ChapterDirective;

/**
 * Step 2 — chapter writing. Chapters are generated sequentially so each one is
 * aware of what earlier chapters already covered.
 */
public final class ChapterPrompts {

    private ChapterPrompts() {
    }

    /**
     * How a premium book ends. Used for the final chapter (and by the editor for
     * it), so the last pages read as the deliberate conclusion of an edited book,
     * not the point where generation stopped.
     */
    public static final String ENDING_ARCHITECTURE = """
            ENDING ARCHITECTURE — this chapter is where the book ends. Build its close
            deliberately, choosing the parts that fit this book's type and tone:
            1. SYNTHESIS — bring the book's major ideas together into a coherent
               whole. Show how they connect; do NOT recite the table of contents or
               summarise chapter by chapter.
            2. PRACTICAL NEXT STEP — answer "what should I actually do now?": a
               final action plan, a short implementation plan (e.g. the next 7
               days), a routine, a framework, or a "start here tomorrow" section.
               A :::steps block suits this well.
            3. COMPLETION CHECK (when it fits) — a compact :::checklist the reader
               can use to confirm they have applied or understood the material. It
               must feel like part of the book, not an appended form.
            4. FINAL TAKEAWAY — one concise idea that reinforces the book's central
               promise (a :::key-idea or :::pullquote may carry it).
            5. INTENTIONAL CLOSING — a final paragraph written as the last moment of
               the book: motivating, reflective, practical, decisive or calm to match
               the tone. It must make the reader feel they reached the end of
               something complete.
            Never close with generic lines such as "That's all!", "I hope you
            enjoyed this ebook", "Thank you for reading", "This concludes the book"
            or "Good luck on your journey!" unless that tone genuinely fits. Never
            reference chapters, sections, bonuses or follow-ups that do not exist.
            The final sentence must be complete and must be the book's last word.
            """;

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

    /**
     * @param position      the chapter's 1-based position among the chapters in the book
     * @param totalChapters how many chapters the book contains (deferred ones excluded)
     */
    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String previousSummaries,
                              ChapterDirective directive,
                              String availableImages,
                              int position,
                              int totalChapters) {
        String imagesSection = (availableImages == null || availableImages.isBlank())
                ? ""
                : "\nIMAGES AVAILABLE FOR THIS CHAPTER (use where they fit, or not at all)\n"
                        + availableImages.strip() + "\n";
        int total = Math.max(totalChapters, position);
        String positionSection;
        if (directive.finalChapter()) {
            positionSection = """

                    POSITION IN THE BOOK
                    This is the FINAL chapter (chapter %d of %d) — the book ends here.
                    Cover this chapter's own scope, then bring the book to a natural,
                    satisfying close. Do NOT open large new topics or promise further
                    chapters.

                    %s""".formatted(position, total, ENDING_ARCHITECTURE);
            if (directive.windDown()) {
                positionSection += """

                        SCOPE OF THIS EDITION
                        To keep this book complete within its length, the following planned
                        chapters are NOT part of this book: %s.
                        Do not mention, promise or allude to them, and do not say the book
                        was shortened. Synthesise what the book HAS established, make the
                        practical next step build only on that material, and close the book
                        as a finished whole.
                        """.formatted(String.join("; ", directive.omittedChapters()));
            }
        } else {
            positionSection = """

                    POSITION IN THE BOOK
                    This is chapter %d of %d. Cover this chapter's scope fully and end it
                    at a clean boundary (a finished section, not a cliff-hanger), but do
                    not try to conclude the whole book — later chapters continue it. Only
                    refer forward to chapters that appear in the outline above.
                    """.formatted(position, total);
        }
        String lengthGuidance = directive.tight()
                ? """
                  Length: about %d words. The book is close to its length budget, so
                  treat this as a real limit — plan the chapter to fit, keep the most
                  valuable material, and still finish every section, exercise and
                  sentence you start. Never stop mid-thought.""".formatted(directive.targetWords())
                : """
                  Length: aim for about %d words. This is a guide, not a hard stop —
                  finish every thought, section and exercise naturally even if that
                  runs a little over, and do not pad to reach the number. Do not
                  expand far beyond it either: the book is planned to this scale."""
                        .formatted(directive.targetWords());
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
                %s

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
                position,
                nz(chapter.getTitle()),
                nz(chapter.getDescription()),
                lengthGuidance.strip()
        );
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
