package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;

/**
 * Prompts for knowledge ingestion — OpenAI reading the author's own materials
 * and organising them into STRUCTURED BOOK KNOWLEDGE. Two calls:
 * <ul>
 *   <li><b>extraction</b> — one per batch of source documents;</li>
 *   <li><b>consolidation</b> — only when there were several batches: merges the
 *       per-batch results into one coherent knowledge base and finds the gaps
 *       that are only visible across the whole material.</li>
 * </ul>
 * Neither writes book content: this stage only understands the material. The
 * book itself is written later, by Claude, from the stored knowledge.
 */
public final class KnowledgePrompts {

    private KnowledgePrompts() {
    }

    /** The JSON shape both calls must return (kept in one place so they never drift). */
    static final String SCHEMA = """
            {
              "project": {
                "name": "short name of what the materials are about (a project, course, practice…)",
                "type": "e.g. web application, online course, recipe collection, research notes",
                "description": "2-4 sentences: what it is and what it does",
                "domain": "subject area",
                "technologies": ["tools, languages, frameworks, methods actually used — empty if not applicable"],
                "sources": ["SOURCE labels"]
              },
              "summary": "4-8 sentences: what these materials teach or show, as input for planning a book",
              "topics": [{"name": "", "description": "what the materials say about it", "importance": "high|medium|low", "sources": [""]}],
              "processes": [{"name": "", "description": "", "steps": ["ordered concrete steps"], "sources": [""]}],
              "examples": [{"title": "", "description": "what it demonstrates", "kind": "code|scenario|case|data|other", "snippet": "short verbatim excerpt (max ~20 lines) or null", "sources": [""]}],
              "importantDetails": [{"detail": "", "whyItMatters": "why it probably belongs in the book", "sources": [""]}],
              "terminology": [{"term": "", "definition": "as used in these materials", "sources": [""]}],
              "userInsights": [{"insight": "the author's own experience, in plain words", "kind": "problem|decision|mistake|tip|opinion|experience", "sources": [""]}],
              "technicalDetails": [{"area": "", "detail": "concrete specifics: versions, settings, structure, numbers", "sources": [""]}],
              "facts": [{"fact": "a concrete, checkable statement from the materials", "sources": [""]}],
              "intendedSequence": [{"step": "what comes at this point, in the author's intended order", "sources": [""]}],
              "knowledgeGaps": [{"question": "what the book will need that the materials do not explain", "whyItMatters": "", "relatedTopic": "", "sources": [""]}]
            }
            """;

    private static final String RULES = """
            RULES
            - Use ONLY what the provided materials and the brief say. Never invent features,
              steps, numbers, experiences or opinions. If something seems relevant but is not
              explained, record it under knowledgeGaps instead of guessing.
            - Every item must cite its origin in "sources", using the exact SOURCE labels shown
              in the material headers (e.g. "src/main/java/com/shop/security/SecurityConfig.java"
              or "user-notes"). Cite several labels when several sources support an item.
            - Be specific to THIS material. "The project uses JWT; tokens are created in
              JwtService and checked by JwtAuthFilter" is useful. "Spring Boot is a popular
              framework" is not — do not write generic background knowledge.
            - The author's notes ("user-notes") are a first-class source even when they are
              rough, misspelled or in another language. Interpret what they mean: an ordered
              list of what to cover becomes intendedSequence; "I had problems with X" becomes a
              userInsight of kind problem (and X is usually an important topic); "need to explain
              why Y" is an importantDetail and, if the materials do not explain why, a
              knowledgeGap.
            - Look at the material through the brief: what will THIS book, for THIS audience,
              with THIS goal, need from it?
            - For code: describe what the code does and how it is organised (entities,
              endpoints, configuration, flows) — do not just list file names. Keep snippets
              short and only where they are a genuinely useful example.
            - The archive structure document shows how the material is organised; use it for
              project structure, but cite real files where you can.
            - Do not write book text, chapters or an outline. Organise knowledge only.
            - Write every value in English, regardless of the materials' language (the book's
              language is handled later). Keep each list focused: the most relevant items
              first, at most about 25 items per list.
            - Return a single JSON object with exactly the fields of the schema. Use empty
              arrays where nothing applies.
            """;

    public static String extractionSystem() {
        return """
                You are the knowledge-ingestion engine of Scrivetta, an app that turns an author's
                own knowledge into a book. You read a batch of the author's materials (notes,
                documents, project files, source code) and extract structured knowledge for the
                book planner. You do not write the book.

                Return JSON with this shape:
                """ + SCHEMA + "\n" + RULES;
    }

    /**
     * @param batchIndex    1-based batch number
     * @param batchCount    number of batches in this run
     * @param notesContext  an excerpt of the author's notes, repeated in batches
     *                      after the first so code can be read in the light of
     *                      what the author said (null when not needed)
     * @param material      the batch's documents, each wrapped in a SOURCE header
     */
    public static String extractionUser(Ebook brief, int batchIndex, int batchCount,
                                        String notesContext, String material) {
        StringBuilder sb = new StringBuilder();
        sb.append(briefSection(brief));
        if (batchCount > 1) {
            sb.append("\nThis is batch ").append(batchIndex).append(" of ").append(batchCount)
                    .append(" of the author's materials. Extract what THIS batch contains; other")
                    .append(" batches are analysed separately and merged afterwards.\n");
        }
        if (notesContext != null && !notesContext.isBlank()) {
            sb.append("""

                    AUTHOR'S NOTES (context only — already analysed in batch 1; cite "user-notes"
                    only if an item in THIS batch confirms something the notes say)
                    ---
                    """).append(notesContext.strip()).append("\n---\n");
        }
        sb.append("\nMATERIALS\n").append(material);
        return sb.toString();
    }

    public static String consolidationSystem() {
        return """
                You are the knowledge-ingestion engine of Scrivetta. The author's materials were
                too large for one pass, so they were analysed in batches. You receive the merged
                (but still raw) results of all batches. CONSOLIDATE them into one coherent
                knowledge base for the book planner:
                - merge items that describe the same thing (keep the most specific wording and
                  the union of their "sources"); drop exact repetitions;
                - keep every userInsight and every intendedSequence step from the author unless
                  it is a true duplicate — they are the author's own voice;
                - write a project description and summary that cover the WHOLE material;
                - re-check knowledgeGaps against the whole material: remove gaps that another
                  batch answers, add gaps that only become visible now;
                - never introduce information that is not in the input;
                - keep "sources" labels exactly as given.

                Return JSON with this shape:
                """ + SCHEMA + "\n" + RULES;
    }

    public static String consolidationUser(Ebook brief, String mergedJson) {
        return briefSection(brief) + "\nRAW MERGED KNOWLEDGE FROM ALL BATCHES (JSON)\n" + mergedJson;
    }

    static String briefSection(Ebook e) {
        return """
                BOOK BRIEF (what the author wants to write)
                Working title / topic: %s
                Book goal / purpose: %s
                Target audience: %s
                Language of the future book: %s
                Style: %s
                Additional instructions: %s
                """.formatted(
                nz(e.getTopic()), nz(e.getBookGoal()), nz(e.getTargetAudience()), nz(e.getLanguage()),
                nz(e.getStyle()), nz(e.getAdditionalInstructions()));
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(not provided)" : s.strip();
    }
}
