package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;

/**
 * Step 1 — book planning. Produces the outline as strict JSON.
 */
public final class PlanningPrompts {

    private PlanningPrompts() {
    }

    public static String system(String language) {
        return PromptGuidelines.core(language) + """

                You are planning a complete non-fiction ebook.
                Return ONLY a single JSON object — no prose, no markdown fences, no
                commentary before or after it. The JSON MUST match this shape exactly:

                {
                  "title": "string — a strong, specific working title",
                  "subtitle": "string — a clarifying subtitle",
                  "targetAudience": "string — who this book is for",
                  "description": "string — 2-4 sentence back-cover description",
                  "writingGuidelines": "string — concrete voice/tone/formatting guidance that every chapter must follow, tailored to the topic, audience and style",
                  "chapters": [
                    {
                      "title": "string — chapter title",
                      "description": "string — 2-4 sentences: what this chapter covers and why, and how it differs from adjacent chapters so nothing is repeated",
                      "approxPages": 0
                    }
                  ]
                }

                Your job is to design a COMPLETE book: a coherent arc that opens the
                topic, develops it, and ends with a real conclusion. The length is a
                RESULT of covering the topic well, not a number to hit.

                Rules for the plan:
                - Choose a sensible number of chapters for a complete treatment of
                  the topic. Order them so the book builds logically.
                - ALWAYS end the book with a natural concluding chapter (a wrap-up /
                  conclusion / final chapter) that ties the key ideas together and
                  closes the book. Never leave the arc open-ended, and never plan a
                  book that would stop partway through the topic.
                - Set each chapter's "approxPages" (an integer). Size the WHOLE book
                  — every chapter plus the conclusion — to fit within the page range
                  in the brief. The upper bound there is a HARD MAXIMUM: the sum of
                  all "approxPages" MUST NOT exceed it. If the topic is broad, cover
                  it at the right altitude and still conclude within the budget
                  rather than starting material you cannot finish. Do NOT pad with
                  filler to reach the range — a complete book that lands a little
                  under is better than a padded one, and far better than one cut off.
                - Make chapter scopes distinct and non-overlapping.
                - COMPLETE THE PROMISED STRUCTURE. If the title, topic or
                  instructions promise a fixed structure (e.g. a "7-day plan", a
                  "10-step guide", a "30-day challenge", "5 principles"), the plan
                  MUST cover every one of those units — all seven days, all ten
                  steps — never a partial subset. Give each unit its own chapter (or
                  clearly group them), size the pages so they all fit the budget, and
                  make the units small and even rather than dropping the later ones.
                  A book that promises seven days but stops at day three is a
                  failure, no matter how good the first three are.
                """;
    }

    public static String user(Ebook e, int targetLowPages, int targetHighPages, int maxPages) {
        return user(e, targetLowPages, targetHighPages, maxPages, "");
    }

    public static String user(Ebook e, int targetLowPages, int targetHighPages, int maxPages,
                              String structureHint) {
        String structureSection = (structureHint == null || structureHint.isBlank())
                ? ""
                : "\nPROMISED STRUCTURE (must be fully covered)\n" + structureHint.strip() + "\n";
        return """
                Create the plan for an ebook based on this brief.

                Topic:
                %s

                Target audience:
                %s

                Desired writing style:
                %s

                Expected length: a complete book on this topic is typically around
                %d–%d pages. Treat this as orientation, not a quota — let the topic
                decide the natural length within it. Do not pad to reach it. Whatever
                length you choose, the sum of all chapters' approxPages MUST NOT
                exceed %d pages under any circumstances (a HARD LIMIT), and the book
                MUST reach a proper conclusion within that limit — plan the ending in,
                never let the book run out of room mid-topic.

                Language:
                %s

                Additional instructions:
                %s
                %s
                Optional source material / examples:
                %s
                """.formatted(
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                targetLowPages,
                targetHighPages,
                maxPages,
                blankToEnglish(e.getLanguage()),
                nz(e.getAdditionalInstructions()),
                structureSection,
                nz(e.getSourceMaterial())
        );
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
