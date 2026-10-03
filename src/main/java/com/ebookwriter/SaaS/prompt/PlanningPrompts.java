package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.service.ebook.ScopeEstimate;

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

                Your job is to design a COMPLETE book at the DEPTH the user selected:
                a coherent arc that opens the topic, develops it, and ends with a real
                conclusion. The user chose how deep the book goes, NOT how many pages it
                has — the length is yours to determine from what the content needs.

                Rules for the plan:
                - Depth is the scope control. It decides how many side topics are
                  included, how many examples, how long the explanations are and how
                  much of the source material is used. Plan every chapter the subject
                  genuinely needs at that depth — no more, no less.
                - There is NO page target and NO page limit. Do not shrink the book to
                  look short and do not pad it to look long. Never drop or compress
                  important material just to save pages, and never add filler
                  chapters or repeat content to make the book bigger.
                - Set each chapter's "approxPages" (an integer) to the room that
                  chapter's content honestly needs at this depth (one page holds
                  about 200 words in this book's 6×9" layout). The sum is the
                  book's planned length — a result of the plan, not a number to hit.
                - Order chapters so the book builds logically.
                - ALWAYS end the book with a deliberately designed final chapter (or
                  closing section of the last chapter) that gives the book its ending.
                  Its description must name what it contains, chosen to fit the book:
                  a SYNTHESIS of the key ideas (not a table-of-contents recap), a
                  PRACTICAL NEXT STEP (an action plan, routine, framework or "start here
                  tomorrow"), where it fits a compact COMPLETION CHECKLIST, and a FINAL
                  TAKEAWAY that reinforces the book's central promise. Never leave the
                  arc open-ended, and never plan a book that would stop partway.
                - Make chapter scopes distinct and non-overlapping.
                - COMPLETE THE PROMISED STRUCTURE. If the title, topic or
                  instructions promise a fixed structure (e.g. a "7-day plan", a
                  "10-step guide", a "30-day challenge", "5 principles"), the plan
                  MUST cover every one of those units — all seven days, all ten
                  steps — never a partial subset. Give each unit its own chapter (or
                  clearly group them), give every unit the room it needs, and make the
                  units even rather than dropping the later ones.
                  A book that promises seven days but stops at day three is a
                  failure, no matter how good the first three are.
                """;
    }

    /**
     * @param depth          the depth the user selected (the scope control)
     * @param estimate       Scrivetta's length estimate from the brief and materials —
     *                       orientation for the planner, explicitly not a target
     * @param structureHint  a promised structure that must be covered, or blank
     */
    public static String user(Ebook e, BookDepth depth, ScopeEstimate estimate, String structureHint) {
        String structureSection = (structureHint == null || structureHint.isBlank())
                ? ""
                : "\nPROMISED STRUCTURE (must be fully covered)\n" + structureHint.strip() + "\n";
        return """
                Create the plan for an ebook based on this brief.

                Topic:
                %s

                Book goal / purpose:
                %s

                Target audience:
                %s

                Desired writing style:
                %s

                DEPTH (the user's scope choice)
                %s

                SCOPE ORIENTATION (not a target, not a limit)
                From the brief and the provided material, Scrivetta expects a book of
                this kind to need roughly %d–%d pages in about %d–%d chapters. Use this
                only to calibrate your sense of scale. If the subject at this depth
                genuinely needs more or fewer pages, plan what it needs — the real
                length follows the content.

                Language:
                %s

                Additional instructions:
                %s
                %s
                Optional source material / examples:
                %s
                """.formatted(
                nz(e.getTopic()),
                nz(e.getBookGoal()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                depth.plannerGuidance(),
                estimate.pagesLow(),
                estimate.pagesHigh(),
                estimate.chaptersLow(),
                estimate.chaptersHigh(),
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
