package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the small context strings (outline, chapter summaries) that are fed
 * into the writing and editing prompts.
 */
final class ManuscriptContext {

    private ManuscriptContext() {
    }

    /** A compact outline: "N. Title — description" per chapter. */
    static String outline(List<EbookChapter> chapters) {
        return chapters.stream()
                .map(c -> "%d. %s — %s".formatted(
                        c.getChapterNumber(),
                        nz(c.getTitle()),
                        nz(c.getDescription())))
                .collect(Collectors.joining("\n"));
    }

    /** Summaries of chapters before {@code currentNumber} that already have one. */
    static String previousSummaries(List<EbookChapter> chapters, int currentNumber) {
        return chapters.stream()
                .filter(c -> c.getChapterNumber() < currentNumber)
                .filter(c -> c.getSummary() != null && !c.getSummary().isBlank())
                .map(c -> "Chapter %d (%s): %s".formatted(
                        c.getChapterNumber(), nz(c.getTitle()), c.getSummary().trim()))
                .collect(Collectors.joining("\n"));
    }

    /** Summaries of every chapter except {@code currentNumber} (for the editor). */
    static String otherSummaries(List<EbookChapter> chapters, int currentNumber) {
        return chapters.stream()
                .filter(c -> c.getChapterNumber() != currentNumber)
                .filter(c -> c.getSummary() != null && !c.getSummary().isBlank())
                .map(c -> "Chapter %d (%s): %s".formatted(
                        c.getChapterNumber(), nz(c.getTitle()), c.getSummary().trim()))
                .collect(Collectors.joining("\n"));
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "" : s.trim();
    }
}
