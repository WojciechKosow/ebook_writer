package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.BookDepth;

/**
 * Scope assessment — before anything is written, OpenAI judges how long a
 * complete, high-quality book on this brief and these materials needs to be at
 * each depth. Returns strict JSON. Its answer is bounded in code
 * ({@code ScopeEstimator.combine}), so a generous guess can never become a
 * 500-page book.
 */
public final class ScopePrompts {

    private ScopePrompts() {
    }

    public static String system() {
        StringBuilder depths = new StringBuilder();
        for (BookDepth d : BookDepth.values()) {
            depths.append("- ").append(d.plannerGuidance()).append('\n');
        }
        return """
                You are Scrivetta's scope analyst. Before a non-fiction ebook is written, you judge
                how many pages a COMPLETE, high-quality book needs for the given brief and the
                author's materials, at each of three depths. One page holds about 200 words
                (6×9" layout, headings, examples and exercises included).

                DEPTHS
                %s
                Rules:
                - The length must follow the content. Count the distinct, substantial things the
                  book has to explain (topics, processes, examples, decisions, code walkthroughs)
                  and how much room each needs at each depth.
                - Be conservative. Never inflate. Do not pad a narrow topic or thin materials into a
                  big book. Books over 200 pages are rare and need materials that genuinely contain
                  that much distinct, substantial content; over 300 pages almost never.
                - Do not shrink rich materials into a short summary either: at COMPREHENSIVE the
                  author's material must fit with room to explain it.
                - When the book is written from the author's materials, they define the scope: what
                  they did not provide is not invented.
                - Give a realistic range per depth (low ≤ high), QUICK < STANDARD < COMPREHENSIVE.

                Return ONLY this JSON object:
                {
                  "quick": {"pagesLow": 0, "pagesHigh": 0},
                  "standard": {"pagesLow": 0, "pagesHigh": 0},
                  "comprehensive": {"pagesLow": 0, "pagesHigh": 0},
                  "contentAmount": "small|medium|large|very_large",
                  "rationale": "one or two sentences, in English, on what drives the length"
                }
                """.formatted(depths);
    }

    /**
     * @param brief      the brief (topic, goal, audience, instructions)
     * @param materials  a compact description of the materials (sizes, knowledge, structure), or blank
     */
    public static String user(String brief, String materials) {
        return """
                BRIEF
                %s

                MATERIALS
                %s

                Estimate the page range for each depth.
                """.formatted(brief.strip(),
                materials == null || materials.isBlank() ? "(none — the book is written from the brief alone)"
                        : materials.strip());
    }
}
