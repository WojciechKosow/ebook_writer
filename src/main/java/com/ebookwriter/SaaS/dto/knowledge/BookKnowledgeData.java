package com.ebookwriter.SaaS.dto.knowledge;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * STRUCTURED BOOK KNOWLEDGE — what Scrivetta understood from the author's
 * materials, organised for the next stage (Book Blueprint) rather than as one
 * prose summary. Stored as JSON on {@code BookKnowledge.knowledgeJson}.
 *
 * <p>Every knowledge item carries {@code sources}: references to the material it
 * came from — a path inside an uploaded archive
 * ({@code src/main/java/.../SecurityConfig.java}), an uploaded filename
 * ({@code notes.md}) or {@code user-notes} for the pasted notes. References are
 * validated against the real materials, so they can be trusted for blueprinting,
 * follow-up questions and later verification.
 *
 * <p>Extensible: unknown fields are ignored on read and {@link #schemaVersion}
 * is bumped when the shape changes meaningfully.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookKnowledgeData(
        int schemaVersion,
        BookInfo book,
        ProjectInfo project,
        @JsonAlias("summary") String overallSummary,
        List<Topic> topics,
        List<ProcessInfo> processes,
        List<Example> examples,
        List<ImportantDetail> importantDetails,
        List<Term> terminology,
        List<UserInsight> userInsights,
        List<TechnicalDetail> technicalDetails,
        List<Fact> facts,
        /** The order the author intends to teach/tell things in (often from the notes). */
        List<SequenceStep> intendedSequence,
        List<KnowledgeGap> knowledgeGaps,
        /** Every document that was considered, with whether it was analysed. */
        List<SourceRef> sources,
        Coverage coverage
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public BookKnowledgeData {
        topics = nn(topics);
        processes = nn(processes);
        examples = nn(examples);
        importantDetails = nn(importantDetails);
        terminology = nn(terminology);
        userInsights = nn(userInsights);
        technicalDetails = nn(technicalDetails);
        facts = nn(facts);
        intendedSequence = nn(intendedSequence);
        knowledgeGaps = nn(knowledgeGaps);
        sources = nn(sources);
    }

    static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The author's brief, copied in so the knowledge is self-describing. {@code depth}
     * is the selected {@code BookDepth}; older knowledge carried a page target here,
     * which is ignored on read.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookInfo(String workingTitle, String language, String targetAudience,
                           String goal, String style, String depth) {
    }

    /** What the materials are about as a whole (a project, a course, a practice…). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectInfo(String name, String type, String description, String domain,
                              List<String> technologies, List<String> sources) {
        public ProjectInfo {
            technologies = nn(technologies);
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Topic(String name, String description, String importance, List<String> sources) {
        public Topic {
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcessInfo(String name, String description, List<String> steps, List<String> sources) {
        public ProcessInfo {
            steps = nn(steps);
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Example(String title, String description, String kind, String snippet,
                          List<String> sources) {
        public Example {
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportantDetail(String detail, String whyItMatters, List<String> sources) {
        public ImportantDetail {
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Term(String term, String definition, List<String> sources) {
        public Term {
            sources = nn(sources);
        }
    }

    /**
     * Personal experience: problems hit, decisions made, mistakes to warn about,
     * opinions, tips. {@code kind} is one of problem / decision / mistake / tip /
     * opinion / experience.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserInsight(String insight, String kind, List<String> sources) {
        public UserInsight {
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TechnicalDetail(String area, String detail, List<String> sources) {
        public TechnicalDetail {
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fact(String fact, List<String> sources) {
        public Fact {
            sources = nn(sources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SequenceStep(String step, List<String> sources) {
        public SequenceStep {
            sources = nn(sources);
        }
    }

    /** Something the book seems to need that the materials do not explain. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnowledgeGap(String question, String whyItMatters, String relatedTopic,
                               List<String> sources) {
        public KnowledgeGap {
            sources = nn(sources);
        }
    }

    /**
     * One document considered for the analysis. {@code ref} is the identifier
     * knowledge items cite; {@code origin} names the uploaded source it came from.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceRef(String ref, String origin, String sourceType, String kind,
                            String language, int chars, boolean truncated,
                            boolean analyzed, String duplicateOf, String notAnalyzedReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coverage(int sourcesTotal, int documentsTotal, int documentsAnalyzed,
                           int documentsNotAnalyzed, int duplicates, int filesSkippedAtExtraction,
                           int chunks, long analyzedChars) {
    }

    /** A copy with the deterministic bookkeeping (brief, sources, coverage) replaced. */
    public BookKnowledgeData withBookkeeping(BookInfo book, List<SourceRef> sources, Coverage coverage) {
        return new BookKnowledgeData(CURRENT_SCHEMA_VERSION, book, project, overallSummary, topics,
                processes, examples, importantDetails, terminology, userInsights, technicalDetails,
                facts, intendedSequence, knowledgeGaps, sources, coverage);
    }
}
