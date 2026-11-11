package com.ebookwriter.SaaS.entity;

/**
 * Which model writes the book — the user's choice of how much AI goes into the
 * prose. Independent of {@link BookDepth} (how much the book covers) and of the
 * subscription plan (Basic / Pro), hence the "model" names.
 * <ul>
 *   <li>{@link #BASIC} — "Basic model": the premium model prepares the book —
 *       it plans it and writes a detailed brief for every chapter from the
 *       author's material — and a lighter model writes and edits the chapters
 *       from those briefs. Cheaper per page. The default for new books.</li>
 *   <li>{@link #BETTER} — "Better model": the premium model plans, writes and
 *       edits every chapter itself. The original pipeline; the standard price.</li>
 * </ul>
 * Model ids and effort levels are configuration ({@code AnthropicProperties});
 * the page price per quality is {@code credits.pricing.*}.
 */
public enum WritingQuality {

    BASIC("Basic model",
            "The top model prepares your book chapter by chapter; a faster model writes it. Lower price."),

    BETTER("Better model",
            "The top model plans and writes every chapter itself, going deeper into your material.");

    private final String label;
    private final String description;

    WritingQuality(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The default for a new book when the user did not choose. */
    public static WritingQuality defaultForNewBook(WritingQuality chosen) {
        return chosen == null ? BASIC : chosen;
    }

    /**
     * The quality of a stored book. A book stored before the choice existed was
     * written by the premium model, so it continues with it ({@link #BETTER}).
     */
    public static WritingQuality orStored(WritingQuality stored) {
        return stored == null ? BETTER : stored;
    }
}
