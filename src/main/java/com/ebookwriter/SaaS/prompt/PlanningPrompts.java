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
                - Set each chapter's "approxPages" (an integer) so the sum is close to
                  the requested total page count.
                - Make chapter scopes distinct and non-overlapping.
                """;
    }

    public static String user(Ebook e) {
        return """
                Create the plan for an ebook based on this brief.

                Topic:
                %s

                Target audience:
                %s

                Desired writing style:
                %s

                Approximate length:
                %d pages

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
                e.getApproxPageCount(),
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
