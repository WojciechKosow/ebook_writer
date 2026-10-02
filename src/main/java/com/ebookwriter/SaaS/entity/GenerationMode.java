package com.ebookwriter.SaaS.entity;

/**
 * How a book is generated.
 * <ul>
 *   <li>{@link #LEGACY} — the original flow: Claude plans and writes the book
 *       from the brief alone (topic, audience, style, instructions). Also what a
 *       {@code null} mode means, so every pre-existing book stays legacy.</li>
 *   <li>{@link #KNOWLEDGE} — the knowledge-based flow: the chapter structure
 *       comes from the author's BLUEPRINT_READY Book Blueprint and Claude writes
 *       each chapter from the author's BookKnowledge, answers and selected source
 *       excerpts (no Claude planning call).</li>
 * </ul>
 */
public enum GenerationMode {
    LEGACY,
    KNOWLEDGE
}
