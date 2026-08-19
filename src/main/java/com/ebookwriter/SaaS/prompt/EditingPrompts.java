package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

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

                Rules:
                - Improve the manuscript WITHOUT changing the author's intended topic
                  or scope, and without inventing new facts, sources or tangents.
                - Preserve correct code and technical detail; fix it only if wrong.
                - Keep the same Markdown formatting conventions (no chapter title or
                  number heading; "##"/"###" for sections; fenced code with a language
                  tag).
                - Return ONLY the full, improved chapter in Markdown. No commentary,
                  no summary, no delimiters.
                """;
    }

    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String otherSummaries) {
        return """
                GLOBAL WRITING GUIDELINES
                %s

                FULL BOOK OUTLINE (context only)
                %s

                SUMMARIES OF THE OTHER CHAPTERS (context only — check for overlap/contradiction)
                %s

                CHAPTER %d: %s
                Intended scope: %s

                CURRENT CHAPTER TEXT
                %s

                Return the improved chapter now.
                """.formatted(
                nz(e.getWritingGuidelines()),
                nz(fullOutline),
                otherSummaries == null || otherSummaries.isBlank()
                        ? "(none)" : otherSummaries.trim(),
                chapter.getChapterNumber(),
                nz(chapter.getTitle()),
                nz(chapter.getDescription()),
                nz(chapter.getContent())
        );
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }
}
