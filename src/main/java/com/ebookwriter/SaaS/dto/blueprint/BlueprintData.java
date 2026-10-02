package com.ebookwriter.SaaS.dto.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * BOOK BLUEPRINT — the plan of a book before it is written, stored as JSON on
 * {@code BookBlueprint.blueprintJson}.
 *
 * <p>A map from the book's structure to the author's knowledge, not a copy of
 * it: each {@link Chapter} says what it is for, what it covers, which
 * {@code BookKnowledge} items it uses ({@link KnowledgeRef}) and which source
 * documents those come from ({@code sourceReferences} — the same refs as
 * {@code BookKnowledgeData.sources}). {@link Gap}s mark what the book needs but
 * the author has not provided; the questions about them (and the answers) are
 * stored separately as {@code BlueprintQuestion} rows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlueprintData(
        int schemaVersion,
        /** What the book is, in 1–2 sentences. */
        String concept,
        String workingTitle,
        String subtitle,
        String audience,
        /** What the reader wants to achieve. */
        String readerGoal,
        /** The book's promise: what the reader can do / will have after reading it. */
        String promise,
        /** Why the chapters are in this order (short; e.g. "follows the author's build order"). */
        String structureRationale,
        List<Chapter> chapters,
        List<Gap> knowledgeGaps,
        /** Top-level fields the author edited by hand (kept on regeneration). */
        List<String> userEditedFields
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public BlueprintData {
        chapters = nn(chapters);
        knowledgeGaps = nn(knowledgeGaps);
        userEditedFields = nn(userEditedFields);
    }

    static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    /**
     * One planned chapter.
     *
     * @param id                  stable id (survives reordering and edits)
     * @param order               1-based position
     * @param purpose             what the chapter is for (1–2 sentences, not book text)
     * @param topics              what it covers
     * @param keyPoints           the specific things from the author's knowledge it must convey, each sourced
     * @param knowledgeReferences the BookKnowledge items it uses
     * @param sourceReferences    the source documents behind them
     * @param gapIds              knowledge gaps affecting this chapter
     * @param origin              AI (proposed by Scrivetta) or AUTHOR (added by the author)
     * @param edited              true once the author changed it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chapter(String id, int order, String title, String purpose, List<String> topics,
                          List<KeyPoint> keyPoints, List<KnowledgeRef> knowledgeReferences,
                          List<String> sourceReferences, List<String> gapIds, String origin, boolean edited) {
        public Chapter {
            topics = nn(topics);
            keyPoints = nn(keyPoints);
            knowledgeReferences = nn(knowledgeReferences);
            sourceReferences = nn(sourceReferences);
            gapIds = nn(gapIds);
        }

        public Chapter withOrder(int newOrder) {
            return new Chapter(id, newOrder, title, purpose, topics, keyPoints, knowledgeReferences,
                    sourceReferences, gapIds, origin, edited);
        }
    }

    /** A specific point from the author's knowledge a chapter must convey (short, not prose). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyPoint(String point, List<String> sources) {
        public KeyPoint {
            sources = nn(sources);
        }
    }

    /**
     * A pointer into BookKnowledge: {@code type} is the list (topic, process,
     * example, importantDetail, term, userInsight, technicalDetail, fact,
     * intendedSequence, knowledgeGap) and {@code name} the item's name/text there.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnowledgeRef(String type, String name) {
    }

    /**
     * Something the book needs that the author's materials do not provide.
     *
     * @param severity   critical (book can't be correct without it) / important / minor
     * @param status     OPEN, ANSWERED (the author answered its question), SKIPPED (declined),
     *                   or NOT_ASKED (low priority — left open, never invented)
     * @param questionId the question asked about it, if any
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gap(String id, String description, String whyItMatters, String severity,
                      List<String> chapterIds, String status, String questionId) {
        public Gap {
            chapterIds = nn(chapterIds);
        }

        public Gap withStatus(String newStatus, String newQuestionId) {
            return new Gap(id, description, whyItMatters, severity, chapterIds, newStatus, newQuestionId);
        }
    }

    public BlueprintData withChapters(List<Chapter> newChapters) {
        return new BlueprintData(schemaVersion, concept, workingTitle, subtitle, audience, readerGoal, promise,
                structureRationale, newChapters, knowledgeGaps, userEditedFields);
    }

    public BlueprintData withGaps(List<Gap> newGaps) {
        return new BlueprintData(schemaVersion, concept, workingTitle, subtitle, audience, readerGoal, promise,
                structureRationale, chapters, newGaps, userEditedFields);
    }
}
