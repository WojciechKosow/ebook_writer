package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.ChapterDirective;
import com.ebookwriter.SaaS.service.ebook.KnowledgeChapterContext;

/**
 * Chapter writing for knowledge-based books: Claude writes one chapter from the
 * author's own knowledge (BookKnowledge), the approved blueprint and the
 * author's answers — not from general knowledge of the topic. Shares the
 * formatting, design-component, summary and ending rules of {@link ChapterPrompts}
 * and adds the source hierarchy and the factual discipline.
 */
public final class KnowledgeChapterPrompts {

    private KnowledgeChapterPrompts() {
    }

    /** Marker so logs/tests can tell knowledge-based chapter requests apart. */
    public static final String MARKER = "SOURCE HIERARCHY";

    static final String RULES = """

            THIS BOOK IS BUILT FROM THE AUTHOR'S OWN KNOWLEDGE
            The author has a real project / real experience. The reader must feel: "I am learning
            from someone who actually built this" — never "I am reading generic information".

            SOURCE HIERARCHY — follow it strictly
            1. THE AUTHOR'S KNOWLEDGE (highest): "AUTHOR'S KNOWLEDGE FOR THIS CHAPTER", "THE
               AUTHOR'S ANSWERS" and "SOURCE FILES". This is what the chapter is about. Use the
               project's real names (classes, entities, endpoints, config keys, files), its real
               steps, decisions, problems and solutions.
            2. THE BLUEPRINT: what this chapter is for, its topics, its place in the book.
            3. GENERAL KNOWLEDGE (lowest): only for correct language, simple definitions, obvious
               connections and smooth explanations. Never as a substitute for the author's
               project or experience.

            CONCRETE, NOT GENERIC
            - Explain THIS project: "In this project, SecurityConfig registers JwtAuthFilter
              before…" — not paragraphs about why authentication matters in general.
            - Code: base every code example on SOURCE FILES / examples given. Keep the real
              names and structure; you may shorten or simplify for teaching, but never rename
              things (no "Item" when the project has "Product") and never invent files, classes,
              endpoints or dependencies the materials do not show. If a file is not given,
              describe it instead of fabricating its code.
            - Follow the author's order of work where it applies to this chapter.

            THE AUTHOR'S EXPERIENCE MUST SURVIVE
            - Items marked AUTHOR'S OWN EXPERIENCE and the author's answers are the most valuable
              material. Tell them in the author's voice ("The first problem I ran into was…"),
              keeping what went wrong, why, how it was fixed and the lesson. If the requested
              style is formal, attribute them neutrally ("In this project the filter was first…")
              but keep their substance. Never drop them.

            WHAT THE AUTHOR DID NOT PROVIDE
            - "NOT PROVIDED BY THE AUTHOR" lists open gaps. Never invent the author's reasons,
              stories, results or experiences to fill them. If the point is not needed, leave
              it out. If the explanation needs it, explain it neutrally as general knowledge,
              NOT as the author's decision: write "JWT is one approach to authentication; in
              this project it is used to…", not "I chose JWT because…" (unless an answer says
              why).
            - Never invent benchmarks, numbers, results, customers, success stories or claims
              about what the author did.

            STYLE IS NOT KNOWLEDGE
            - The style changes how things are said, never the facts.
            - Write everything (headings, prose, captions, comments in explanations) in the
              book's language, but never translate code, identifiers, file names or commands.

            CONTINUITY
            - Use the names from TERMINOLOGY and from earlier chapters' summaries exactly; build on
              what earlier chapters established instead of re-introducing it.
            - In the summary after the delimiter, end with a line "Names introduced: …" listing
              the classes, entities, endpoints, files and terms this chapter introduced.
            """;

    public static String system(String language) {
        return ChapterPrompts.system(language) + RULES;
    }

    public static String user(Ebook e, String outline, EbookChapter chapter, String previousSummaries,
                              KnowledgeChapterContext.Context ctx, ChapterDirective directive,
                              String availableImages, int position, int totalChapters) {
        String imagesSection = (availableImages == null || availableImages.isBlank()) ? ""
                : "\nIMAGES AVAILABLE FOR THIS CHAPTER (use where they fit, or not at all)\n" + availableImages.strip() + "\n";
        return """
                %s
                THE BOOK
                %s
                Language: %s
                Style: %s
                Additional instructions: %s

                GLOBAL WRITING GUIDELINES
                %s

                BOOK STRUCTURE (the approved blueprint — do not rewrite other chapters)
                %s

                WHAT EARLIER CHAPTERS ALREADY ESTABLISHED (build on them; do not repeat)
                %s

                CHAPTER TO WRITE NOW
                Chapter %d: %s
                Purpose and scope: %s

                AUTHOR'S KNOWLEDGE FOR THIS CHAPTER (priority 1)
                %s

                THE AUTHOR'S ANSWERS (priority 1 — the author's own words)
                %s

                SOURCE FILES FROM THE AUTHOR'S PROJECT (priority 1 — real code/config for this chapter)
                %s

                NOT PROVIDED BY THE AUTHOR (do not invent; see the rules)
                %s
                %s%s
                %s

                Write this chapter now.
                """.formatted(
                MARKER + " applies: the author's knowledge first, then the blueprint, then general knowledge.",
                ctx.book(),
                blankToEnglish(e.getLanguage()),
                nz(e.getStyle()),
                nz(e.getAdditionalInstructions()),
                nz(e.getWritingGuidelines()),
                nz(outline),
                previousSummaries == null || previousSummaries.isBlank() ? "(this is the first chapter)" : previousSummaries.strip(),
                position,
                nz(chapter.getTitle()),
                nz(chapter.getDescription()),
                orNone(ctx.knowledge()),
                orNone(ctx.answers()),
                orNone(ctx.excerpts()),
                ctx.gaps().isBlank() ? "(nothing open for this chapter)" : ctx.gaps(),
                imagesSection,
                ChapterPrompts.positionSection(directive, position, totalChapters),
                ChapterPrompts.lengthGuidance(directive, e.getDepth()).strip());
    }

    /**
     * Added to the editorial pass for knowledge-based books, so editing keeps the
     * book's specificity instead of smoothing it into generic prose.
     */
    public static String editingAddendum(String bookContext) {
        return """

                THIS BOOK IS BASED ON THE AUTHOR'S OWN PROJECT AND EXPERIENCE
                - Keep every project-specific name (classes, entities, endpoints, files, config keys),
                  step, decision, problem and solution exactly. Do not replace them with generic
                  wording or rename anything.
                - Keep the author's experiences and lessons (first person or attributed); never cut them
                  to "tighten" the text.
                - Do not add facts, reasons, numbers or stories the chapter does not already contain.

                BOOK CONTEXT (for consistency checks only)
                """ + bookContext + "\n";
    }

    private static String orNone(String s) {
        return s == null || s.isBlank() ? "(none for this chapter)" : s.strip();
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.strip();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.strip();
    }
}
