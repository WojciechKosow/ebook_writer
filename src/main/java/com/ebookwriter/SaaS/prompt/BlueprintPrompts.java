package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.entity.Ebook;

/**
 * Prompts for the Book Blueprint — OpenAI planning a book from the author's
 * stored BookKnowledge (not from general knowledge of the topic), mapping the
 * knowledge to chapters, finding the gaps and choosing the few questions worth
 * asking. No book text is written here; Claude writes the book later.
 */
public final class BlueprintPrompts {

    private BlueprintPrompts() {
    }

    static final String SCHEMA = """
            {
              "concept": "what this book is, in 1-2 sentences",
              "workingTitle": "a clear, specific title (keep the author's if it works)",
              "subtitle": "optional subtitle or null",
              "audience": "who the book is for",
              "readerGoal": "what the reader wants to achieve",
              "promise": "what the reader will be able to do / will have built after reading",
              "structureRationale": "1-2 sentences: why the chapters are in this order",
              "chapters": [{
                "key": "c1",
                "sameAsChapterId": "id from CURRENT STRUCTURE when this is the same chapter, else null",
                "title": "specific chapter title",
                "purpose": "1-2 sentences: what this chapter is for",
                "topics": ["what it covers"],
                "keyPoints": [{"point": "a specific thing from the author's knowledge this chapter must convey", "sources": ["source refs"]}],
                "knowledgeReferences": [{"type": "topic|process|example|importantDetail|term|userInsight|technicalDetail|fact|intendedSequence|knowledgeGap", "name": "exact name/text of the item in BOOK KNOWLEDGE"}],
                "sourceReferences": ["exact refs from VALID SOURCE REFS"],
                "gapKeys": ["g1"]
              }],
              "knowledgeGaps": [{
                "key": "g1",
                "description": "what exactly is missing, in terms of what the author said or provided",
                "whyItMatters": "what the book loses without it",
                "severity": "critical|important|minor",
                "chapterKeys": ["c5"],
                "canBeInferred": false
              }],
              "questions": [{
                "gapKey": "g1",
                "chapterKey": "c5",
                "question": "one short, concrete question to the author",
                "reason": "why Scrivetta asks (one short sentence)",
                "priority": 1
              }],
              "answerLinks": [{"questionId": "id of an already-answered question", "chapterKey": "c5"}]
            }
            """;

    public static String system(int maxQuestions) {
        return """
                You are the book planner of Scrivetta, an app that turns an AUTHOR'S OWN KNOWLEDGE into
                a book. You receive the book brief and the structured knowledge Scrivetta extracted from
                the author's materials (notes, documents, project files). You design the Book Blueprint:
                the chapter structure, which of the author's knowledge each chapter uses, what is
                missing, and the few questions worth asking the author. You do NOT write the book.

                Return JSON with this shape:
                """ + SCHEMA + """

                THE BOOK MUST REFLECT THE AUTHOR'S ACTUAL KNOWLEDGE
                - Build the structure from BOOK KNOWLEDGE, not from what a typical book on this topic
                  contains. If the author built a specific project, the chapters follow THAT project
                  (its real parts, files, decisions). Never add a chapter about a feature, step or
                  component that the knowledge does not contain — even if such books usually have it.
                - If the knowledge has an intendedSequence (the author's own order), follow it unless
                  it clearly contradicts the goal; say so in structureRationale.
                - Assign every userInsight (the author's problems, decisions, mistakes, tips) to the
                  chapter where it belongs, via knowledgeReferences and keyPoints.
                - Every chapter must name the knowledge it uses (knowledgeReferences, using the exact
                  type and name from BOOK KNOWLEDGE) and the sources behind it (sourceReferences, only
                  refs from VALID SOURCE REFS). A short orientation chapter (e.g. "What we're building")
                  is fine when it is grounded in the materials; generic chapters like "Introduction",
                  "Basics", "Advanced topics", "Conclusion" with no grounding are not.
                - Chapter titles are specific ("Securing the API with JWT", not "Security").
                - Use the brief's depth to decide the structure (see DEPTH and CHAPTER GUIDANCE).
                - purpose and keyPoints are short planning notes, never book prose.

                KNOWLEDGE GAPS
                - A gap is something THIS book needs that the author has not provided:
                  * the author mentions something but does not explain it ("had problems with JWT":
                    we don't know what went wrong, how it was fixed, or the lesson for the reader);
                  * a chapter needs information that is not in the materials;
                  * an important process is incomplete; a decision is made but the reason is unknown;
                  * an example is mentioned without details;
                  * the author's experience would clearly make a chapter better.
                - Do NOT fill a gap with your own general knowledge presented as the author's. Missing
                  author knowledge is a gap, not a fact.
                - Set canBeInferred=true when the materials let a writer reasonably work it out (e.g.
                  the Java version is in pom.xml). Never ask about those.
                - Consider the knowledgeGaps already listed in BOOK KNOWLEDGE, refine them in terms of
                  the chapters, and drop those the knowledge itself answers.

                QUESTIONS — A FEW, NOT A FORM
                - Ask at most %d questions in total; fewer is better. Choose the gaps that matter most,
                  in this priority order:
                  1 = needed to understand what the book is / must deliver,
                  2 = needed to describe a process correctly,
                  3 = the author's experience that gives the reader real value,
                  4 = an important example,
                  5 = extra detail that improves quality.
                - Each question is short, concrete, easy to answer and tied to what the author said or
                  provided. Bad: "Please provide more information about authentication." Good: "You
                  mentioned JWT caused problems. What went wrong, and how did you fix it?" Better:
                  "What was your biggest mistake implementing JWT, and what should beginners avoid?"
                - One question per gap at most; do not ask about something an existing answer covers.
                - It is fine to return no gaps and no questions when the materials are complete.

                ALREADY ANSWERED
                - AUTHOR'S ANSWERS are the author's own knowledge: use them in the chapters (keyPoints)
                  and link each one to its chapter in answerLinks. Do not ask them again.

                AUTHOR'S EDITS
                - If CURRENT STRUCTURE marks chapters as [edited by author] or [added by author], keep
                  them: same title, purpose and relative position. You may still map knowledge to them.
                - For every chapter that corresponds to one in CURRENT STRUCTURE, set sameAsChapterId to
                  its id (even if you would name it differently) — never propose it twice.
                - Keep AUTHOR-CONFIRMED fields exactly as given.

                Write all values in the language of the future book (see brief). Return one JSON object
                with exactly the schema's fields.
                """.formatted(Math.max(0, maxQuestions));
    }

    /**
     * @param knowledgeJson     compact BookKnowledge JSON
     * @param validRefs         the citable source refs (one per line)
     * @param confirmedFields   author-confirmed top-level fields, or blank
     * @param answers           already-answered questions, or blank
     * @param currentStructure  the current chapters with author-edit markers, or blank
     */
    public static String user(Ebook e, String knowledgeJson, String validRefs, String confirmedFields,
                              String answers, String currentStructure) {
        BookDepth depth = e.effectiveDepth();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                BOOK BRIEF
                Working title / topic: %s
                Book goal / purpose: %s
                Target audience: %s
                Language of the book: %s
                Style: %s
                Additional instructions: %s

                DEPTH (the author's scope choice — there is no page target)
                %s

                CHAPTER GUIDANCE
                Let the author's knowledge define the chapters. Give every substantial area of the
                material the chapter(s) it needs at this depth: at QUICK merge minor areas and leave out
                secondary ones; at COMPREHENSIVE cover the material broadly and split dense areas.
                Never pad with chapters the knowledge cannot support, and never merge or drop
                significant material just to keep the book short.
                """.formatted(nz(e.getTopic()), nz(e.getBookGoal()), nz(e.getTargetAudience()),
                nz(e.getLanguage()), nz(e.getStyle()), nz(e.getAdditionalInstructions()),
                depth.plannerGuidance()));
        if (confirmedFields != null && !confirmedFields.isBlank()) {
            sb.append("\nAUTHOR-CONFIRMED (keep exactly)\n").append(confirmedFields.strip()).append('\n');
        }
        if (currentStructure != null && !currentStructure.isBlank()) {
            sb.append("\nCURRENT STRUCTURE (the author has reviewed this blueprint)\n").append(currentStructure.strip()).append('\n');
        }
        if (answers != null && !answers.isBlank()) {
            sb.append("\nAUTHOR'S ANSWERS (already given — use them, do not ask again)\n").append(answers.strip()).append('\n');
        }
        sb.append("\nVALID SOURCE REFS\n").append(validRefs.strip()).append('\n');
        sb.append("\nBOOK KNOWLEDGE (JSON)\n").append(knowledgeJson).append('\n');
        return sb.toString();
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(not provided)" : s.strip();
    }
}
