package com.ebookwriter.SaaS.service.ebook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a unit of content (a chapter) is complete enough to typeset. Every
 * rule is structural and independent of the book's genre: it reads sentence
 * ends, open blocks, headings and lead-ins, never the subject matter.
 * <ul>
 *   <li>{@link Kind#TRUNCATED} — the model stopped at its output limit and could
 *       not be continued;</li>
 *   <li>{@link Kind#UNCLOSED_BLOCK} — a code fence or {@code :::} block is never
 *       closed;</li>
 *   <li>{@link Kind#ENDS_MID_SENTENCE} — the last prose block stops without
 *       closing punctuation;</li>
 *   <li>{@link Kind#ENDS_ON_HEADING} — the unit ends on a heading;</li>
 *   <li>{@link Kind#ANNOUNCED_BLOCK_MISSING} — a lead-in that announces a block
 *       ("…looks like this:") is followed by a heading or by nothing;</li>
 *   <li>{@link Kind#DANGLING_REFERENCE} — the book's last paragraph points at
 *       content that does not follow (final chapter only).</li>
 * </ul>
 * Pure and static, used both before a chapter is accepted and by the final
 * quality report.
 */
public final class CompletenessCheck {

    private CompletenessCheck() {
    }

    public enum Kind {
        EMPTY, TRUNCATED, UNCLOSED_BLOCK, ENDS_MID_SENTENCE, ENDS_ON_HEADING, ANNOUNCED_BLOCK_MISSING,
        DANGLING_REFERENCE
    }

    /** One finding, with the text it was found at. */
    public record Problem(Kind kind, String excerpt) {
        public String describe() {
            String what = switch (kind) {
                case EMPTY -> "no content";
                case TRUNCATED -> "generation stopped at the output limit";
                case UNCLOSED_BLOCK -> "a block is opened but never closed";
                case ENDS_MID_SENTENCE -> "ends mid-sentence";
                case ENDS_ON_HEADING -> "ends on a heading with nothing under it";
                case ANNOUNCED_BLOCK_MISSING -> "announces content that does not follow";
                case DANGLING_REFERENCE -> "the book's last paragraph refers to content that does not exist";
            };
            return excerpt == null || excerpt.isBlank() ? what : what + " (\"" + excerpt + "\")";
        }
    }

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+.*$");
    private static final Pattern FENCE = Pattern.compile("^(```|~~~).*$");
    private static final Pattern DIRECTIVE = Pattern.compile("^:::.*$");

    /**
     * Problems in {@code markdown}.
     *
     * @param finalChapter the unit ends the book (checks for a dangling forward reference)
     */
    public static List<Problem> inspect(String markdown, boolean finalChapter) {
        List<Problem> problems = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            problems.add(new Problem(Kind.EMPTY, null));
            return problems;
        }
        List<String> lines = List.of(markdown.strip().split("\n", -1));
        int unclosed = ManuscriptIntegrity.unclosedBlockStart(lines);
        if (unclosed >= 0) {
            problems.add(new Problem(Kind.UNCLOSED_BLOCK, excerpt(lines.get(unclosed))));
        }
        List<String> blocks = ManuscriptIntegrity.toBlocks(lines);

        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i).strip();
            if (!isProse(block) || !endsWithLeadIn(block)) {
                continue;
            }
            String next = i + 1 < blocks.size() ? blocks.get(i + 1).strip() : null;
            if (next == null || HEADING.matcher(next.split("\n")[0]).matches()) {
                problems.add(new Problem(Kind.ANNOUNCED_BLOCK_MISSING, tail(block)));
            }
        }

        String last = blocks.get(blocks.size() - 1).strip();
        String lastLine = last.split("\n")[last.split("\n").length - 1].strip();
        if (HEADING.matcher(lastLine).matches()) {
            problems.add(new Problem(Kind.ENDS_ON_HEADING, excerpt(lastLine)));
        } else if (unclosed < 0 && isProse(last) && !ManuscriptIntegrity.endsComplete(last)) {
            problems.add(new Problem(Kind.ENDS_MID_SENTENCE, tail(last)));
        }
        if (finalChapter && ManuscriptIntegrity.hasDanglingForwardReference(markdown)) {
            problems.add(new Problem(Kind.DANGLING_REFERENCE, tail(last)));
        }
        return problems;
    }

    /** A paragraph of running text (not a heading, list, table, image, code or component). */
    static boolean isProse(String block) {
        String first = block.split("\n")[0].strip();
        return !first.isEmpty()
                && !HEADING.matcher(first).matches()
                && !LIST_ITEM.matcher(first).matches()
                && !FENCE.matcher(first).matches()
                && !DIRECTIVE.matcher(first).matches()
                && !first.startsWith("|")
                && !first.startsWith("![")
                && !first.startsWith(">");
    }

    /** Ends in a colon (after any closing emphasis): a lead-in to what comes next. */
    static boolean endsWithLeadIn(String block) {
        String t = block.strip().replaceAll("[*_\\s]+$", "");
        return t.endsWith(":") || t.endsWith("：");
    }

    private static String tail(String block) {
        String t = block.strip().replaceAll("\\s+", " ");
        return t.length() > 80 ? t.substring(t.length() - 80) : t;
    }

    private static String excerpt(String line) {
        String t = line.strip();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
