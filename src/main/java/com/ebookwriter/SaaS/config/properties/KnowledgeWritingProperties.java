package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Size limits for what each Claude chapter request receives in knowledge-based
 * generation. They keep every request small but sufficient: the book-level
 * context, only the knowledge relevant to the chapter, its answers and gaps,
 * and short excerpts of its own source files — never the whole upload.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "knowledge-writing")
public class KnowledgeWritingProperties {

    /** Book-level context (title, promise, project, chapter list, terminology). */
    private int maxBookContextChars = 6_000;

    /** The author's knowledge selected for one chapter. */
    private int maxChapterKnowledgeChars = 12_000;

    /** The author's answers given to one chapter. */
    private int maxAnswersChars = 6_000;

    /** Source files quoted per chapter (code/config/docs the blueprint mapped to it). */
    private int maxExcerpts = 3;

    /** Characters per quoted source file. */
    private int maxExcerptChars = 3_500;

    /** Characters of source excerpts per chapter in total. */
    private int maxExcerptTotalChars = 9_000;
}
