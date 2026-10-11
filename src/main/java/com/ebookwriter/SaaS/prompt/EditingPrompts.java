package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.WritingContext;

/**
 * Step 3 — editorial pass. Runs per chapter with awareness of the whole book so
 * cross-chapter issues (repetition, contradictions, terminology drift) can be
 * caught without holding the entire manuscript in one request.
 */
public final class EditingPrompts {

    private EditingPrompts() {
    }

    public static String system(String language) {
        return PromptGuidelines.core(language) + """

                You are the book's editor. You are given one chapter to improve.
                Check for and fix:
                - Repetition (within the chapter and versus what other chapters cover)
                - Contradictions and inconsistent terminology
                - Poor transitions and weak or hand-wavy explanations
                - Unnecessary filler
                - Structural problems or drift from the requested style
                - Anything from the chapter's intended scope that is missing
                - References to chapters or sections that are NOT in the outline
                  (e.g. "in Chapter 7 we will…" when there is no such chapter):
                  rewrite or remove them so nothing points at missing content
                - An ending that trails off: every section, exercise, list and the
                  final sentence must be complete
                - A topic the TOPIC REGISTRY shows was already explained in an earlier
                  chapter and is explained again here: replace the re-explanation with
                  a short reference back ("see Chapter N") and keep only what is new
                - A warning, tip or example box that repeats one given earlier: remove it
                - Recurring closing sections that do not match the BOOK TEMPLATE

                Rules:
                - Improve the manuscript WITHOUT changing the author's intended topic
                  or scope, and without inventing new facts, sources or tangents.
                - Preserve correct code and technical detail; fix it only if wrong.
                - Keep the same Markdown formatting conventions (no chapter title or
                  number heading; "##"/"###" for sections; fenced code with a language
                  tag).
                - Return ONLY the full, improved chapter in Markdown. No commentary,
                  no summary, no delimiters.

                """ + ChapterPrompts.COMPLETENESS_RULES + "\n" + ChapterPrompts.verbatimRules();
    }

    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String otherSummaries) {
        return user(e, fullOutline, chapter, otherSummaries, false);
    }

    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String otherSummaries,
                              boolean finalChapter) {
        return user(e, fullOutline, chapter, otherSummaries, finalChapter, null);
    }

    /** As above; {@code knowledgeAddendum} (knowledge-based books) is appended before the chapter text. */
    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String otherSummaries,
                              boolean finalChapter,
                              String knowledgeAddendum) {
        return user(e, fullOutline, chapter, otherSummaries, finalChapter, knowledgeAddendum, WritingContext.NONE);
    }

    /** As above, edited against the book's topic registry and template ({@link WritingContext}). */
    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String otherSummaries,
                              boolean finalChapter,
                              String knowledgeAddendum,
                              WritingContext context) {
        String endingSection = finalChapter
                ? "\nTHIS IS THE BOOK'S FINAL CHAPTER. Keep (or, if missing, strengthen) a "
                        + "deliberate ending that fits the book, and never weaken or remove it:\n"
                        + ChapterPrompts.ENDING_ARCHITECTURE + "\n"
                : "";
        return """
                GLOBAL WRITING GUIDELINES
                %s

                FULL BOOK OUTLINE (context only)
                %s

                SUMMARIES OF THE OTHER CHAPTERS (context only — check for overlap/contradiction)
                %s
                %s

                CHAPTER %d: %s
                Intended scope: %s
                Book depth: %s Keep the chapter at this depth — remove filler and
                repetition, but never shorten substantive content to make the book shorter.

                %s
                CURRENT CHAPTER TEXT
                %s

                Return the improved chapter now.
                """.formatted(
                nz(e.getWritingGuidelines()),
                nz(fullOutline),
                otherSummaries == null || otherSummaries.isBlank()
                        ? "(none)" : otherSummaries.trim(),
                ChapterPrompts.bookContextSections(context, chapter.getChapterNumber()),
                chapter.getChapterNumber(),
                nz(chapter.getTitle()),
                nz(chapter.getDescription()),
                e.effectiveDepth().label() + ".",
                endingSection + (knowledgeAddendum == null ? "" : knowledgeAddendum),
                nz(chapter.getContent())
        );
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }
}
