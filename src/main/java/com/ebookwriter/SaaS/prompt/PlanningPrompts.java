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

                Rules for the plan:
                - Choose a sensible number of chapters for the target length. Order
                  them so the book builds logically.
                - Set each chapter's "approxPages" (an integer). The sum of all
                  chapters' "approxPages" is a HARD MAXIMUM: it MUST NOT exceed the
                  maximum page count given in the brief. Aim to use most of that
                  budget, but never go over it. Fewer, shorter chapters are better
                  than breaching the limit.
                - Make chapter scopes distinct and non-overlapping.
                """;
    }

    public static String user(Ebook e, int targetPages, int maxPages) {
        return """
                Create the plan for an ebook based on this brief.

                Topic:
                %s

                Target audience:
                %s

                Desired writing style:
                %s

                Target length: aim for the sum of all chapters' approxPages to be
                about %d pages — this is the length the reader asked for, so plan
                to hit it, not to pad beyond it. You may go a little higher only if
                the material genuinely needs it, but the sum MUST NOT exceed %d
                pages under any circumstances (a HARD LIMIT).

                Language:
                %s

                Additional instructions:
                %s

                Optional source material / examples:
                %s
                """.formatted(
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                targetPages,
                maxPages,
                blankToEnglish(e.getLanguage()),
                nz(e.getAdditionalInstructions()),
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
