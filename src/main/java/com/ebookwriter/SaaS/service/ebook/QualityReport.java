package com.ebookwriter.SaaS.service.ebook;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The author's quality report for one rendered book: what a reviewer should
 * look at before publishing, each item located by chapter and page. Produced
 * after every render ({@link BookQualityInspector}) and served to the author.
 *
 * @param generatedAt     ISO-8601 time of the render
 * @param pages           pages in the rendered PDF
 * @param plannedPages    pages the plan budgeted (null when unknown)
 * @param errors          findings of {@link Severity#ERROR} — the book should not be published as is
 * @param warnings        findings of {@link Severity#WARNING}
 * @param readyToPublish  true when there are no errors
 * @param findings        every finding, most severe first, then in book order
 * @param lengths         planned vs rendered pages per chapter
 * @param repeatedPhrases the phrases most often repeated across chapters
 */
public record QualityReport(String generatedAt, int pages, Integer plannedPages, int errors, int warnings,
                            boolean readyToPublish, List<Finding> findings, List<ChapterLength> lengths,
                            List<RepeatedPhrase> repeatedPhrases) {

    public enum Severity { ERROR, WARNING, INFO }

    public enum Category {
        /** A chapter or section that is unfinished (point 1). */
        INCOMPLETE_UNIT,
        /** A heading with nothing under it (point 2). */
        EMPTY_HEADING,
        /** A list number or bullet standing alone, or a leftover internal marker (point 2). */
        ORPHAN_LIST_MARKER,
        /** The same block printed more than once in the book (point 2). */
        DUPLICATE_BLOCK,
        /** A recurring section missing from a chapter, or present only in some (point 2). */
        TEMPLATE_MISMATCH,
        /** Reader-visible text that matches an internal field (brief, summary, notes) (point 3). */
        INTERNAL_LEAK,
        /** Reader text that ends in an ellipsis, i.e. was cut off (point 3). */
        TRUNCATED_READER_TEXT,
        /** A verbatim line wider than the page: set smaller or carried over (point 4). */
        VERBATIM_OVERFLOW,
        /** The same topic explained, or the same phrase used, in several chapters (point 5). */
        REPETITION,
        /** Length off its budget (point 6). */
        LENGTH_BUDGET
    }

    /**
     * One finding.
     *
     * @param chapter the chapter number (null for the book as a whole)
     * @param page    the PDF page it is on (null when it has no printed position, e.g. text not printed)
     */
    public record Finding(Category category, Severity severity, Integer chapter, String chapterTitle, Integer page,
                          String message, String excerpt) {
    }

    /** Planned vs rendered length of one chapter; {@code deviation} is (actual − planned) / planned. */
    public record ChapterLength(int chapter, String title, int plannedPages, int actualPages, double deviation) {
    }

    /** A phrase repeated across chapters: how often, and in which chapters. */
    public record RepeatedPhrase(String phrase, int occurrences, List<Integer> chapters) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the quality report", e);
        }
    }

    public static QualityReport fromJson(String json) {
        try {
            return MAPPER.readValue(json, QualityReport.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the quality report", e);
        }
    }

    public List<Finding> of(Category category) {
        return findings.stream().filter(f -> f.category() == category).toList();
    }
}
