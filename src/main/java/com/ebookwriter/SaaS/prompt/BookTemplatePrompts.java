package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.List;
import java.util.Map;

/**
 * Step 1.2 — the book template and the reader-facing chapter texts. Runs once,
 * after the outline exists (for briefed and blueprint books alike):
 * <ul>
 *   <li>which recurring sections every chapter ends with — or none — decided
 *       once for the whole book;</li>
 *   <li>a short subtitle per chapter, written for the reader at its final
 *       length. The outline's chapter briefs are instructions for the writer;
 *       they are shown here as input only and are never printed.</li>
 * </ul>
 */
public final class BookTemplatePrompts {

    private BookTemplatePrompts() {
    }

    public static String system(String language, int maxSubtitleChars) {
        return PromptGuidelines.core(language) + """

                As this book's editor-in-chief, decide its chapter template and write
                the short texts the reader sees under each chapter title. Return ONLY a single JSON object, no prose, no markdown fences:

                {
                  "recurringSections": [
                    {"heading": "exact section heading, in the book's language",
                     "purpose": "what this section contains in every chapter"}
                  ],
                  "chapters": [
                    {"number": 1, "subtitle": "text printed under chapter 1's title"}
                  ]
                }

                RECURRING SECTIONS
                - Decide ONCE, for the whole book, which closing sections EVERY chapter
                  ends with (for example a short practice section or a recap), in which
                  order, and with which exact heading. Choose only sections that
                  genuinely serve this book's purpose, genre and readers.
                - An empty list is a valid answer and often the right one. At most %d
                  sections. Do not include the chapter's own opening.
                - Every chapter will carry exactly these sections, so choose only what
                  makes sense for every chapter, the last one included.

                CHAPTER SUBTITLES
                - One subtitle per chapter, written FOR THE READER: what the chapter
                  offers them, in the book's voice and language. It is not a summary of
                  instructions and never addresses a writer ("Orient the reader…",
                  "Teach how…", "This chapter should…" are wrong).
                - At most %d characters. Write it complete at that length — never cut
                  off, never ending in an ellipsis. Prefer a single phrase or sentence.
                - Do not repeat the chapter title.
                """.formatted(com.ebookwriter.SaaS.service.ebook.BookTemplate.MAX_SECTIONS, maxSubtitleChars);
    }

    public static String user(Ebook e, List<EbookChapter> chapters) {
        StringBuilder outline = new StringBuilder();
        for (EbookChapter c : chapters) {
            outline.append(c.getChapterNumber()).append(". ").append(nz(c.getTitle())).append('\n')
                    .append("   Brief (internal, not for the reader): ").append(nz(c.getDescription())).append('\n');
        }
        return """
                BOOK
                Title: %s
                Subtitle: %s
                Topic: %s
                Audience: %s
                Style: %s
                Language: %s
                Additional instructions: %s

                CHAPTERS
                %s
                Decide the template and write the subtitles now.
                """.formatted(nz(e.getTitle()), nz(e.getSubtitle()), nz(e.getTopic()), nz(e.getTargetAudience()),
                nz(e.getStyle()), blankToEnglish(e.getLanguage()), nz(e.getAdditionalInstructions()), outline);
    }

    /** Ask again, only for the subtitles that came back too long. */
    public static String shorten(Map<Integer, String> tooLong, int maxSubtitleChars) {
        StringBuilder list = new StringBuilder();
        tooLong.forEach((n, s) -> list.append(n).append(": ").append(s).append('\n'));
        return """
                These chapter subtitles are longer than %d characters. Rewrite each one
                so it is complete and at most %d characters long — a shorter phrasing,
                not a cut-off version, and no ellipsis. Same language and meaning.

                %s
                Return ONLY JSON: {"chapters": [{"number": 1, "subtitle": "..."}]}
                """.formatted(maxSubtitleChars, maxSubtitleChars, list);
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
