package com.ebookwriter.SaaS.generation;

import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.prompt.KnowledgeChapterPrompts;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for Claude at the {@code AnthropicService} boundary. Records every
 * request and answers like the writer would — strictly from what the prompt
 * contains: a knowledge-based chapter is written from the knowledge, answers and
 * source files in its prompt; a legacy chapter only has the brief to go on. So a
 * book's content reflects exactly what the pipeline sent.
 */
final class FakeClaude {

    record Call(String system, String user) {
        boolean isKnowledgeChapter() {
            return system.contains(KnowledgeChapterPrompts.MARKER);
        }

        boolean isLegacyChapter() {
            return system.contains("You are writing ONE chapter") && !isKnowledgeChapter();
        }

        boolean isLegacyPlanning() {
            return system.contains("You are planning a complete non-fiction ebook");
        }

        boolean isEditing() {
            return system.contains("You are the book's editor");
        }

        boolean isImagePlanning() {
            return system.contains("planning the illustrations");
        }

        String chapterTitle() {
            Matcher m = Pattern.compile("CHAPTER TO WRITE NOW\\nChapter \\d+: (.+)").matcher(user);
            return m.find() ? m.group(1).strip() : null;
        }
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile Predicate<Call> failWhen = c -> false;
    /** What the legacy planner answers; a test may swap in a larger plan. */
    volatile String legacyPlan = LEGACY_PLAN;
    /** Extra words appended to every legacy chapter (a chapter that runs far longer than planned). */
    volatile int legacyExtraWords = 0;

    String respond(String system, String user) {
        Call call = new Call(system, user);
        calls.add(call);
        if (failWhen.test(call)) {
            throw new RuntimeException("simulated Claude outage");
        }
        if (call.isKnowledgeChapter()) return knowledgeChapter(call);
        if (call.isLegacyChapter()) return legacyChapter(call, legacyExtraWords);
        if (call.isLegacyPlanning()) return legacyPlan;
        if (call.isEditing()) return between(user, "CURRENT CHAPTER TEXT\n", "\n\nReturn the improved chapter now.");
        if (call.isImagePlanning()) return imagePlan(user);
        return "{}";
    }

    List<Call> knowledgeChapterCalls() {
        return calls.stream().filter(Call::isKnowledgeChapter).toList();
    }

    Call chapterCall(String titleFragment) {
        return calls.stream().filter(c -> c.isKnowledgeChapter() || c.isLegacyChapter())
                .filter(c -> c.chapterTitle() != null && c.chapterTitle().contains(titleFragment))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no chapter call for " + titleFragment));
    }

    // ---- answers ----------------------------------------------------------------

    private static String knowledgeChapter(Call c) {
        String title = c.chapterTitle();
        StringBuilder sb = new StringBuilder();
        sb.append("In this chapter we work on ").append(title).append(" in the shop we are building.\n\n");
        String knowledge = between(c.user(), "AUTHOR'S KNOWLEDGE FOR THIS CHAPTER (priority 1)\n", "\n\nTHE AUTHOR'S ANSWERS");
        if (!knowledge.startsWith("(none")) {
            sb.append("## What the project does here\n\n");
            for (String line : knowledge.split("\n")) {
                if (line.startsWith("- ")) sb.append(line.substring(2).replaceAll("\\s+\\[from: .*]$", "")).append(".\n\n");
            }
        }
        Matcher answer = Pattern.compile("A \\(the author's own words\\): (.+)").matcher(c.user());
        if (answer.find()) {
            sb.append("## What went wrong for me\n\nThe first problem I ran into: ").append(answer.group(1).strip()).append("\n\n");
        }
        Matcher file = Pattern.compile("--- FILE: (\\S+)[^\\n]*---\\n((?:.*\\n){1,6})").matcher(c.user());
        if (file.find()) {
            sb.append("## The code\n\nHere is the relevant part of `").append(file.group(1)).append("`:\n\n```java\n")
                    .append(file.group(2).strip()).append("\n```\n\n");
        }
        sb.append(filler(title));
        return sb + "\n" + ChapterPrompts.SUMMARY_DELIMITER + "\nCovered " + title + ". Names introduced: "
                + (file.find(0) ? file.group(1) : "none");
    }

    private static String legacyChapter(Call c, int extraWords) {
        String title = c.chapterTitle();
        String extra = extraWords <= 0 ? "" : "## Going further\n\n" + "More detail here. ".repeat(extraWords / 3) + "\n\n";
        return "Authentication is an important part of modern web applications, and online shops are no exception.\n\n"
                + "## Why it matters\n\n" + filler(title) + "\n" + extra + ChapterPrompts.SUMMARY_DELIMITER
                + "\nGeneric chapter on " + title + ".";
    }

    private static String filler(String title) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("Working through ").append(title).append(" step by step keeps the reader oriented and shows how each ")
                    .append("piece connects to the previous one before moving on to the next part of the build. ");
        }
        return sb.append("\n").toString();
    }

    private static String imagePlan(String user) {
        Matcher m = Pattern.compile("=== Chapter (\\d+): ([^=]+?) ===").matcher(user);
        List<String> images = new ArrayList<>();
        while (m.find()) {
            if (m.group(2).contains("JWT")) {
                images.add("""
                        {"chapterNumber": %s, "anchorHeading": null, "type": "ILLUSTRATION",
                         "purpose": "Show the request flow through the JWT filter",
                         "description": "The shop's JWT request flow",
                         "generationPrompt": "Technical editorial illustration of the online shop's JWT authentication flow",
                         "aspectRatio": "3:2", "priority": 1}""".formatted(m.group(1)));
            }
        }
        return "{\"images\": [" + String.join(",", images) + "]}";
    }

    static final String LEGACY_PLAN = """
            {"title": "Building an Online Shop with Spring Boot", "subtitle": "A practical guide",
             "description": "A guide to online shops with Spring Boot.", "writingGuidelines": "Be clear.",
             "chapters": [
               {"title": "Introduction to Spring Boot", "description": "Basics", "approxPages": 4},
               {"title": "Authentication Basics", "description": "Security", "approxPages": 4},
               {"title": "Building the Catalog", "description": "Products", "approxPages": 4},
               {"title": "Conclusion", "description": "Wrap up", "approxPages": 3}
             ]}
            """;

    static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) return "";
        a += start.length();
        int b = s.indexOf(end, a);
        return (b < 0 ? s.substring(a) : s.substring(a, b)).strip();
    }
}
