package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.service.ebook.ContentBudget;

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

                Your job is to design a COMPLETE book at the scale the user selected:
                a coherent arc that opens the topic, develops it, and ends with a real
                conclusion — sized so the finished book naturally lands around the
                target length.

                Rules for the plan:
                - The target length is a CONTENT BUDGET, not a quota and not a hard
                  stop. Use it to decide how many chapters the book has, how deep each
                  goes, how many exercises, examples and supporting sections it carries.
                  A ~20-page book is focused; a ~50-page book has room for depth; a
                  ~100-page book can be comprehensive. Plan the scope that genuinely
                  fits that size — do NOT plan a much bigger book than was asked for,
                  and do NOT pad a small topic with filler to reach the number.
                - Set each chapter's "approxPages" (an integer) so the sum lands close
                  to the target. Landing a little under or over is fine when the
                  material calls for it; a promised structure (below) may go over.
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
                  clearly group them), size the pages so they all fit, and make the
                  units even rather than dropping the later ones — a promised
                  structure takes priority over hitting the target exactly.
                  A book that promises seven days but stops at day three is a
                  failure, no matter how good the first three are.
                """;
    }

    /**
     * @param budget   the soft content budget derived from the selected target
     * @param maxPages the most content pages the plan may total (the soft limit,
     *                 never above the user's credit ceiling)
     */
    public static String user(Ebook e, ContentBudget budget, int maxPages, String structureHint) {
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

                TARGET LENGTH (soft content budget): about %d pages in total, i.e.
                about %d pages of chapter content. A good result lands roughly in the
                %d–%d page range. Plan for this scale:
                - typically %d–%d chapters (fewer for a simple topic, more for a
                  promised day/step structure);
                - depth: %s;
                - about %d practical component(s) per chapter (exercise, checklist,
                  worked example or action plan) where they genuinely help.
                The sum of all chapters' approxPages must not exceed %d. Do not pad to
                reach the target, and do not expand far beyond it — the book should
                be complete at this scale.

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
                budget.targetPages(),
                budget.contentTarget(),
                budget.rangeLow(),
                budget.rangeHigh(),
                budget.minChapters(),
                budget.maxChapters(),
                budget.depth(),
                budget.exercisesPerChapter(),
                Math.max(1, maxPages),
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
