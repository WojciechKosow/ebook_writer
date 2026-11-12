package com.ebookwriter.SaaS.service.ebook;

/**
 * What a chapter is written (or edited) with beyond its brief: the book's topic
 * registry so far, the book template and — for a Basic-model book — the
 * premium model's writing brief for the chapter.
 *
 * @param topicRegistry what earlier chapters already explained, one
 *                      "Chapter N: topic — gist" line per entry ({@link TopicRegistry}); blank for the first chapter
 * @param template      the book's recurring sections; null when the book has none decided (older books)
 * @param writingBrief  the section-by-section brief the premium model prepared ({@link ChapterBriefService}); blank for none
 */
public record WritingContext(String topicRegistry, BookTemplate template, String writingBrief) {

    public static final WritingContext NONE = new WritingContext("", null, "");

    public WritingContext {
        topicRegistry = topicRegistry == null ? "" : topicRegistry.strip();
        writingBrief = writingBrief == null ? "" : writingBrief.strip();
    }

    /** Without a writing brief (the Better model, the editor, older books). */
    public WritingContext(String topicRegistry, BookTemplate template) {
        this(topicRegistry, template, "");
    }
}
