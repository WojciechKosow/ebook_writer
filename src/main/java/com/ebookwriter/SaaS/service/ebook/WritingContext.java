package com.ebookwriter.SaaS.service.ebook;

/**
 * What a chapter is written (or edited) with beyond its brief: the book's topic
 * registry so far and the book template.
 *
 * @param topicRegistry what earlier chapters already explained, one
 *                      "Chapter N: topic — gist" line per entry ({@link TopicRegistry}); blank for the first chapter
 * @param template      the book's recurring sections; null when the book has none decided (older books)
 */
public record WritingContext(String topicRegistry, BookTemplate template) {

    public static final WritingContext NONE = new WritingContext("", null);

    public WritingContext {
        topicRegistry = topicRegistry == null ? "" : topicRegistry.strip();
    }
}
