package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.ChapterDirective;
import com.ebookwriter.SaaS.service.ebook.KnowledgeChapterContext;
import com.ebookwriter.SaaS.service.ebook.WritingContext;

/**
 * Chapter writing for knowledge-based books — in any field (software,
 * photography, fitness, video, marketing…): Claude writes one chapter from the
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
            The author knows this subject first-hand — a craft they practise, a method they teach,
            a project they built, a routine they follow. The reader must feel: "I am learning from
            someone who actually does this" — never "I am reading generic information".
            The subject can be anything: software, photography, video editing, fitness, cooking,
            marketing, a YouTube channel, a business. Adapt to it; do not assume it is technical.

            SOURCE HIERARCHY — follow it strictly
            1. THE AUTHOR'S KNOWLEDGE (highest): "AUTHOR'S KNOWLEDGE FOR THIS CHAPTER", "THE
               AUTHOR'S ANSWERS" and "THE AUTHOR'S MATERIAL". This is what the chapter is about.
               Use the author's own specifics: their methods, steps, settings, numbers, tools,
               names, decisions, problems, mistakes and solutions.
            2. THE BLUEPRINT: what this chapter is for, its topics, its place in the book.
            3. GENERAL KNOWLEDGE (lowest): only for correct language, simple definitions, obvious
               connections and smooth explanations. Never as a substitute for the author's
               method or experience, and never to stretch the chapter to a length.

            CONCRETE, NOT GENERIC
            - Explain THE AUTHOR'S way of doing it, with their specifics: "Place the subject
              0.5–1.5 m from the window and turn them slightly toward it" — not paragraphs about
              why light matters in general. The same holds in any field: a training plan's real
              sets and reps, an editing workflow's real steps, a project's real classes.
            - Keep the author's own names, values and wording (settings, quantities, tools,
              exercise names, file and class names). You may shorten or simplify for teaching, but
              never rename things and never invent steps, values, tools, files or results the
              material does not show.
            - Technical material: base every code example on the material given; never invent
              files, classes, endpoints or dependencies. If a file is not given, describe it
              instead of fabricating its code. Non-technical material: do not add code, commands
              or technical jargon the subject does not need.
            - Follow the author's order of work where it applies to this chapter.

            THE AUTHOR'S EXPERIENCE MUST SURVIVE
            - Items marked AUTHOR'S OWN EXPERIENCE and the author's answers are the most valuable
              material. Tell them in the author's voice ("When I started, I spent too much time
              on…"), keeping what went wrong, why, how it was solved and the lesson. If the
              requested style is formal, attribute them neutrally ("A common early mistake is…")
              but keep their substance. Never drop them.

            WHAT THE AUTHOR DID NOT PROVIDE
            - "NOT PROVIDED BY THE AUTHOR" lists open gaps. Never invent the author's reasons,
              stories, results or experiences to fill them. If the point is not needed, leave
              it out. If the explanation needs it, explain it neutrally as general knowledge,
              NOT as the author's decision: write "An 85mm lens is a common choice for
              headshots", not "I always shoot with an 85mm because…" (unless an answer says so).
            - Never invent benchmarks, numbers, results, clients, success stories, photos or
              claims about what the author did.

            STAY WITHIN THE MATERIAL
            - The chapter's length follows from what the author gave for it. When the material
              for this chapter is covered well, finish the chapter — shorter than the budget is
              fine. Never fill space with generic background, restated points or a second pass
              over something already said.

            STYLE IS NOT KNOWLEDGE
            - The style changes how things are said, never the facts.
            - Write everything (headings, prose, captions, comments in explanations) in the
              book's language, but never translate code, identifiers, file names or commands.

            CONTINUITY
            - Use the names from TERMINOLOGY and from earlier chapters' summaries exactly; build on
              what earlier chapters established instead of re-introducing it.
            - In the summary after the delimiter, end with a line "Names introduced: …" listing
              the specific terms, techniques, tools and names (classes, files and the like for
              technical books) this chapter introduced.
            """;

    public static String system(String language) {
        return ChapterPrompts.system(language) + RULES;
    }

    public static String user(Ebook e, String outline, EbookChapter chapter, String previousSummaries,
                              KnowledgeChapterContext.Context ctx, ChapterDirective directive,
                              String availableImages, int position, int totalChapters) {
        return user(e, outline, chapter, previousSummaries, ctx, directive, availableImages, position,
                totalChapters, WritingContext.NONE);
    }

    /** As above, written with the book's topic registry and template ({@link WritingContext}). */
    public static String user(Ebook e, String outline, EbookChapter chapter, String previousSummaries,
                              KnowledgeChapterContext.Context ctx, ChapterDirective directive,
                              String availableImages, int position, int totalChapters,
                              WritingContext context) {
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
                %s
                CHAPTER TO WRITE NOW
                Chapter %d: %s
                Purpose and scope: %s

                AUTHOR'S KNOWLEDGE FOR THIS CHAPTER (priority 1)
                %s

                THE AUTHOR'S ANSWERS (priority 1 — the author's own words)
                %s

                THE AUTHOR'S MATERIAL FOR THIS CHAPTER (priority 1 — the author's own notes, documents or files, quoted)
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
                ChapterPrompts.bookContextSections(context, chapter.getChapterNumber()),
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

                THIS BOOK IS BASED ON THE AUTHOR'S OWN KNOWLEDGE AND EXPERIENCE
                - Keep every author-specific name, value and step exactly (settings, quantities,
                  tools, techniques; classes, files and config keys in technical books), and every
                  decision, problem and solution. Do not replace them with generic wording or
                  rename anything.
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
