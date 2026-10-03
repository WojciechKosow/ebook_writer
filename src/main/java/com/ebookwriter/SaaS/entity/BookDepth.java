package com.ebookwriter.SaaS.entity;

/**
 * How deep the book should go — the user's <b>only</b> control over the scope of
 * a generation. The user never picks a page count: they choose a depth, Scrivetta
 * analyses the topic and the materials, and the length of the book follows from
 * the content that depth requires (see {@code ScopeEstimator}). The final page
 * count is a <em>result</em> of generation, never a constraint on it.
 *
 * <p>Depth shapes <em>what</em> goes into the book — how many side topics, how
 * many examples, how long the explanations are, how much of the author's material
 * is used — and is applied consistently in the estimate, the plan (or the blueprint
 * sizing) and every chapter prompt. A {@code null} depth on a stored book means
 * {@link #STANDARD}.
 */
public enum BookDepth {

    QUICK("Quick",
            "A short, focused read: the essentials, without extended side topics.",
            "QUICK — a short, focused book. Cover the essentials the reader needs and "
                    + "nothing more: few side topics, the single most useful example where one "
                    + "is needed, short and direct explanations. Be selective with the source "
                    + "material — keep what matters most, leave out secondary detail.",
            "Depth: QUICK. Stay on the essentials of this chapter's scope. Prefer one strong "
                    + "example over several, keep explanations short and direct, and skip side "
                    + "topics and secondary detail."),

    STANDARD("Standard",
            "A full, practical treatment: detailed enough that the reader can actually apply it.",
            "STANDARD — a full, practical book. Cover the topic completely at a normal depth: "
                    + "clear explanations, practical examples and something for the reader to do, "
                    + "detailed enough that the reader can really use it. Use the important parts "
                    + "of the source material; summarise minor ones.",
            "Depth: STANDARD. Cover this chapter's scope fully and practically: explain clearly, "
                    + "show worked examples where they help, and give the reader enough detail to "
                    + "apply it."),

    COMPREHENSIVE("Comprehensive",
            "A complete, in-depth treatment that uses your materials broadly and leaves nothing important out.",
            "COMPREHENSIVE — a complete, in-depth book. Cover the subject broadly and thoroughly: "
                    + "detailed explanations, several examples, edge cases and the context the "
                    + "reader needs. Use the source material extensively and do NOT drop "
                    + "significant information just to keep the book shorter — but never pad: "
                    + "every section must carry real content.",
            "Depth: COMPREHENSIVE. Treat this chapter's scope thoroughly: detailed explanations, "
                    + "several examples, edge cases and supporting context where it is genuinely "
                    + "needed. Use the relevant material extensively and do not leave out important "
                    + "information to save space — but never pad.");

    private final String label;
    private final String description;
    private final String plannerGuidance;
    private final String writerGuidance;

    BookDepth(String label, String description, String plannerGuidance, String writerGuidance) {
        this.label = label;
        this.description = description;
        this.plannerGuidance = plannerGuidance;
        this.writerGuidance = writerGuidance;
    }

    /** Short display name ("Comprehensive"). */
    public String label() {
        return label;
    }

    /** One-line description for the creation UI. */
    public String description() {
        return description;
    }

    /** What the depth means for the book's scope — for planning / blueprint prompts. */
    public String plannerGuidance() {
        return plannerGuidance;
    }

    /** What the depth means for one chapter — for the chapter writer and editor. */
    public String writerGuidance() {
        return writerGuidance;
    }

    /** The stored depth, or {@link #STANDARD} for a book that predates depth. */
    public static BookDepth orDefault(BookDepth depth) {
        return depth == null ? STANDARD : depth;
    }
}
