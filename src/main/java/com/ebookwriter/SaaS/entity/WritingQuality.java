package com.ebookwriter.SaaS.entity;

/**
 * Which model writes the book — the user's choice of how much AI effort goes
 * into the prose. Independent of {@link BookDepth} (how much the book covers)
 * and of the subscription plan.
 * <ul>
 *   <li>{@link #BASIC} — the premium model still analyses and plans the book
 *       (template, subtitles, image and cover plans); a faster, cheaper model
 *       writes and edits the chapters from that plan. The default.</li>
 *   <li>{@link #PRO} — the premium model also writes and edits every chapter:
 *       deeper reasoning per chapter, at a higher AI cost.</li>
 * </ul>
 * The actual model ids and effort levels are configuration
 * ({@code AnthropicProperties}), so they can change without a migration.
 */
public enum WritingQuality {

    BASIC("Basic", "Fast and economical: the top model plans the book, a lighter model writes it."),

    PRO("Pro", "The top model plans and writes every chapter, going deeper into your material.");

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
     * written by the premium model, so it continues with it ({@link #PRO}).
     */
    public static WritingQuality orStored(WritingQuality stored) {
        return stored == null ? PRO : stored;
    }
}
