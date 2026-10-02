package com.ebookwriter.SaaS.dto.blueprint;

import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;

import java.util.List;
import java.util.UUID;

/**
 * The hand-off to the writing stage: everything the writer needs for a book
 * whose blueprint is BLUEPRINT_READY —
 * <b>BookKnowledge + BookBlueprint + the author's answers</b>.
 *
 * <p>The blueprint maps each chapter to knowledge items and source refs inside
 * {@link #knowledge()}; answers are linked to chapters by {@code chapterId}; the
 * {@link #unresolvedGaps()} are things the author did not provide and the
 * writer must not invent.
 */
public record BookGenerationInput(UUID ebookId, BookKnowledgeData knowledge, BlueprintData blueprint,
                                  List<Answer> answers, List<BlueprintData.Gap> unresolvedGaps) {

    /** One answered question — the author's own words. */
    public record Answer(UUID questionId, String chapterId, String gapId, String question, String answer) {
    }

    /** Answers that belong to a chapter (null = book-level answers). */
    public List<Answer> answersFor(String chapterId) {
        return answers.stream().filter(a -> java.util.Objects.equals(a.chapterId(), chapterId)).toList();
    }
}
