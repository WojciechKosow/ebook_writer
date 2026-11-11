package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;

import java.util.List;

/**
 * The "thinking" half of a Basic-model book: the premium model reads the book's
 * plan and the author's material and writes a precise brief for each chapter.
 * A lighter, cheaper model then writes the chapters from those briefs. Several
 * chapters are briefed per request, so the premium model allocates material
 * across the book (no repetition, nothing lost) at a small fraction of the cost
 * of writing it.
 */
public final class BriefPrompts {

    private BriefPrompts() {
    }

    /** The material of one chapter, as the brief request sees it. */
    public record ChapterInput(int number, String title, String scope, int targetWords, String material) {
    }

    public static String system(String language, int maxBriefWords) {
        String lang = (language == null || language.isBlank()) ? "English" : language.strip();
        return """
                You are the lead author of a non-fiction book. You do NOT write the book. A capable
                writer will write each chapter from YOUR BRIEF and the same material, so your brief is
                where the thinking happens: what each chapter says, in which order, with which of the
                author's specifics, and what it leaves to other chapters.

                For EACH chapter you are given, write a brief of at most %d words:
                - SECTIONS: the "##" sections in order, each with one line on what it must establish.
                - THE AUTHOR'S SPECIFICS per section: the exact values, steps, settings, names, tools,
                  experiences, mistakes and examples from the material that belong there (quote short
                  values verbatim — e.g. "f/2–f/2.8, 1/125s, ISO 400–800"). Never invent any.
                - ONE practical element where it fits (an example, an exercise, a checklist), drawn
                  from the material when the material has one.
                - NOT HERE: topics this chapter must leave to another chapter (name the chapter), so
                  the book never explains the same thing twice.
                - GAPS: what the material does not provide and the writer must not invent.
                Plan within the chapter's length; a chapter with little material gets a short brief.
                Write the briefs in English (they are instructions, never printed); keep the author's
                values and terms in their original language. The book itself is written in %s.

                Return ONLY a JSON object:
                {"chapters": [{"chapterNumber": <int>, "brief": "<the brief, plain text with line breaks>"}]}
                One entry for every chapter you were given, none for others.
                """.formatted(maxBriefWords, lang);
    }

    /**
     * @param bookContext the book-level context (title, reader, promise, the author's subject, terminology)
     * @param outline     the whole book's chapter outline
     * @param chapters    the chapters to brief in this request
     */
    public static String user(Ebook e, String bookContext, String outline, List<ChapterInput> chapters) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                THE BOOK
                Topic: %s
                Audience: %s
                Goal: %s
                Style: %s
                Depth: %s
                Additional instructions: %s
                """.formatted(nz(e.getTopic()), nz(e.getTargetAudience()), nz(e.getBookGoal()), nz(e.getStyle()),
                e.effectiveDepth().label(), nz(e.getAdditionalInstructions())));
        if (bookContext != null && !bookContext.isBlank()) {
            sb.append("\nBOOK CONTEXT\n").append(bookContext.strip()).append('\n');
        }
        sb.append("\nTHE WHOLE BOOK (outline — brief only the chapters below)\n").append(nz(outline)).append('\n');
        sb.append("\nCHAPTERS TO BRIEF NOW\n");
        for (ChapterInput c : chapters) {
            sb.append("\n=== Chapter ").append(c.number()).append(": ").append(nz(c.title())).append(" ===\n")
                    .append("Scope: ").append(nz(c.scope())).append('\n')
                    .append("Length: about ").append(c.targetWords()).append(" words\n");
            if (c.material() != null && !c.material().isBlank()) {
                sb.append("Material for this chapter:\n").append(c.material().strip()).append('\n');
            }
        }
        sb.append("\nWrite the briefs now.");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "(none)" : s.strip();
    }
}
