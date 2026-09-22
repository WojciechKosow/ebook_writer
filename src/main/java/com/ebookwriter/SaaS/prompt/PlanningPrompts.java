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
                - Set each chapter's "approxPages" (an integer). Give the topic the
                  room it needs: plan every chapter the material calls for. The brief
                  states a rough typical range and one HARD MAXIMUM for the total —
                  the sum of all "approxPages" MUST NOT exceed that maximum, but you
                  are free to use as much of it as a complete treatment needs. Only if
                  the complete book would exceed the maximum, cover the topic at a
                  higher altitude and still conclude within it rather than starting
                  material you cannot finish. Do NOT pad with filler — a complete book
                  is the goal, never a padded one and never one cut off mid-topic.
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

                Length: cover the topic and this brief COMPLETELY. Plan every
                chapter the material genuinely needs and give each enough pages to be
                worthwhile — if the brief implies many chapters or a broad scope,
                plan them all; do not compress or drop parts to keep the book short.
                Many simple standard ebooks land around %d–%d pages, but that is only
                a rough guide, NOT a target or a cap: a richer topic should be longer.
                Do not pad with filler either — let the topic decide. The ONE hard
                limit is that the sum of all chapters' approxPages must not exceed %d
                pages (the user's available budget); stay within it and always plan a
                proper concluding chapter so the book ends naturally rather than
                running out of room mid-topic. Only trim scope to fit this limit if
                the complete book would genuinely exceed it.

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
