package com.ebookwriter.SaaS.entity;

/**
 * Lifecycle of a book's Book Blueprint (tracked on {@link BookBlueprint}),
 * which starts once the book's knowledge is ready:
 * <pre>
 *   NOT_STARTED → BUILDING_BLUEPRINT → QUESTIONS_REQUIRED → (all answered/skipped) → BLUEPRINT_READY
 *                                    └→ BLUEPRINT_REVIEW   → (author approves)      → BLUEPRINT_READY
 *                                    └→ FAILED (retryable)
 * </pre>
 * {@link #QUESTIONS_REQUIRED} when the planner found gaps worth asking about;
 * {@link #BLUEPRINT_REVIEW} when it found none (the author just reviews and
 * approves). {@link #BLUEPRINT_READY} is the hand-off to the writing stage.
 */
public enum BlueprintStatus {
    NOT_STARTED,
    BUILDING_BLUEPRINT,
    BLUEPRINT_REVIEW,
    QUESTIONS_REQUIRED,
    BLUEPRINT_READY,
    FAILED;

    /** A blueprint exists that the author can look at and edit. */
    public boolean hasBlueprint() {
        return this == BLUEPRINT_REVIEW || this == QUESTIONS_REQUIRED || this == BLUEPRINT_READY;
    }
}
