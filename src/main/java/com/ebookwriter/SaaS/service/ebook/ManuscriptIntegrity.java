package com.ebookwriter.SaaS.service.ebook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guards the <b>semantic integrity</b> of chapter Markdown at its edges, so a
 * book always ends at a clean boundary:
 * <ul>
 *   <li>{@link #repairTruncated} — when a model response was cut off by its
 *       output-token limit, drop the incomplete tail (half a sentence, an
 *       unclosed exercise block, an unterminated code fence, a heading with no
 *       body) back to the last complete block, rather than shipping a chapter
 *       that stops mid-thought.</li>
 *   <li>{@link #trimTrailingOrphans} — a chapter must never end on a heading or
 *       an empty component; those are removed.</li>
 *   <li>{@link #hasDanglingForwardReference} — detects a final paragraph that
 *       points at content which does not follow ("In the next chapter…"), used by
 *       the final quality gate.</li>
 * </ul>
 * Pure and static — no I/O — so every rule is unit-tested.
 */
public final class ManuscriptIntegrity {

    private ManuscriptIntegrity() {
    }

    private static final Pattern DIRECTIVE_OPEN = Pattern.compile("^:::\\s*[a-zA-Z][\\w-]*.*$");
    private static final Pattern DIRECTIVE_CLOSE = Pattern.compile("^:::\\s*$");
    private static final Pattern FENCE = Pattern.compile("^(```|~~~).*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+.*$");

    /**
     * A final paragraph that promises content which isn't there. Matched only in
     * the <em>last</em> paragraph of the book, so legitimate in-book signposting
     * ("in the next section we…" followed by that section) is never flagged.
     */
    private static final Pattern FORWARD_REFERENCE = Pattern.compile(
            "\\b(in the (next|following|coming) (chapter|section|part|day|step|lesson)"
                    + "|(next|following) (chapter|section|part) (will|we)"
                    + "|we will (cover|explore|look at|discuss) .{0,40}\\b(next|later)"
                    + "|to be continued|coming up next|stay tuned)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Characters a complete prose block may end on (incl. closing quotes/brackets). */
    private static final String TERMINAL = ".!?…:;)\"'”’»*_`|";

    /**
     * Repair a chapter whose generation was cut off by the output-token limit:
     * drop any unclosed component or code fence, then trailing blocks that don't
     * end on a complete sentence, then any heading left dangling at the end.
     * Complete material is never touched. Returns the input unchanged if it is
     * already clean, and never returns less than the first block.
     */
    public static String repairTruncated(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        List<String> lines = new ArrayList<>(List.of(markdown.strip().split("\n", -1)));

        // 1. An unclosed ::: component or ``` fence at the tail is incomplete —
        //    remove it entirely (an exercise with half its instructions is worse
        //    than no exercise).
        int unclosed = unclosedBlockStart(lines);
        if (unclosed > 0) {
            lines = new ArrayList<>(lines.subList(0, unclosed));
        }

        List<String> blocks = toBlocks(lines);

        // 2. Drop trailing blocks that stop mid-sentence. A list keeps its complete
        //    items: only the unfinished last item is removed.
        while (blocks.size() > 1 && !endsComplete(blocks.get(blocks.size() - 1))) {
            String last = blocks.remove(blocks.size() - 1);
            String trimmedList = dropIncompleteLastListItem(last);
            if (trimmedList != null) {
                blocks.add(trimmedList);
                break;
            }
        }
        return trimTrailingOrphans(String.join("\n\n", blocks));
    }

    /**
     * Remove material that must never end a chapter: trailing headings with no
     * body and trailing horizontal rules. Leaves everything else untouched.
     */
    public static String trimTrailingOrphans(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        List<String> blocks = toBlocks(List.of(markdown.strip().split("\n", -1)));
        while (blocks.size() > 1) {
            String last = blocks.get(blocks.size() - 1).strip();
            boolean orphanHeading = HEADING.matcher(last).matches() && !last.contains("\n");
            boolean rule = last.matches("^([-*_]\\s*){3,}$");
            if (orphanHeading || rule) {
                blocks.remove(blocks.size() - 1);
            } else {
                break;
            }
        }
        return String.join("\n\n", blocks).strip();
    }

    /** Whether the manuscript's very last paragraph points to content that doesn't follow. */
    public static boolean hasDanglingForwardReference(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return false;
        }
        List<String> blocks = toBlocks(List.of(markdown.strip().split("\n", -1)));
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String b = blocks.get(i).strip();
            if (b.isEmpty() || DIRECTIVE_CLOSE.matcher(b).matches()) {
                continue;
            }
            return FORWARD_REFERENCE.matcher(b).find();
        }
        return false;
    }

    /** Whether the manuscript ends mid-sentence (the last prose block is unterminated). */
    public static boolean endsMidThought(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return false;
        }
        List<String> lines = List.of(markdown.strip().split("\n", -1));
        if (unclosedBlockStart(lines) > 0) {
            return true;
        }
        List<String> blocks = toBlocks(lines);
        String last = blocks.get(blocks.size() - 1);
        // List items are often written without final punctuation; only judge prose.
        if (LIST_ITEM.matcher(last.strip().split("\n")[0]).matches()) {
            return false;
        }
        return !endsComplete(last);
    }

    // ---- internals ------------------------------------------------------------

    /**
     * Index of the line opening a ::: component or code fence that is never
     * closed, or -1 when every block is closed. Fences take precedence: ::: inside
     * a code block is literal text.
     */
    static int unclosedBlockStart(List<String> lines) {
        int openFence = -1;
        int openDirective = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (FENCE.matcher(t).matches()) {
                openFence = openFence < 0 ? i : -1;
                continue;
            }
            if (openFence >= 0) {
                continue;
            }
            if (DIRECTIVE_CLOSE.matcher(t).matches()) {
                openDirective = -1;
            } else if (DIRECTIVE_OPEN.matcher(t).matches()) {
                openDirective = i;
            }
        }
        return openFence >= 0 ? openFence : openDirective;
    }

    /**
     * Split lines into blank-line separated blocks, keeping each ::: component
     * and code fence whole (they may legitimately contain blank lines).
     */
    static List<String> toBlocks(List<String> lines) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;
        boolean inDirective = false;
        for (String line : lines) {
            String t = line.strip();
            if (!inDirective && FENCE.matcher(t).matches()) {
                inFence = !inFence;
            } else if (!inFence && DIRECTIVE_CLOSE.matcher(t).matches()) {
                inDirective = false;
            } else if (!inFence && DIRECTIVE_OPEN.matcher(t).matches()) {
                inDirective = true;
            }
            if (t.isEmpty() && !inFence && !inDirective) {
                if (current.length() > 0) {
                    blocks.add(current.toString().strip());
                    current.setLength(0);
                }
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            blocks.add(current.toString().strip());
        }
        if (blocks.isEmpty()) {
            blocks.add("");
        }
        return blocks;
    }

    /** Whether a block ends at a complete boundary. */
    static boolean endsComplete(String block) {
        String b = block.strip();
        if (b.isEmpty()) {
            return false;
        }
        String[] lines = b.split("\n");
        String first = lines[0].strip();
        String last = lines[lines.length - 1].strip();
        if (DIRECTIVE_CLOSE.matcher(last).matches() && DIRECTIVE_OPEN.matcher(first).matches()) {
            return true; // a closed component
        }
        if (FENCE.matcher(first).matches() && FENCE.matcher(last).matches() && lines.length > 1) {
            return true; // a closed code block
        }
        if (HEADING.matcher(last).matches()) {
            return false; // a heading with nothing under it
        }
        if (last.startsWith("![") && last.endsWith(")")) {
            return true; // an image token
        }
        if (last.startsWith("|") && last.endsWith("|")) {
            return true; // a table row
        }
        char end = last.charAt(last.length() - 1);
        return TERMINAL.indexOf(end) >= 0;
    }

    /**
     * For a list block whose final item is unfinished, return the block without
     * that item (when at least one complete item remains), else null.
     */
    private static String dropIncompleteLastListItem(String block) {
        String[] lines = block.strip().split("\n");
        if (lines.length < 2 || !LIST_ITEM.matcher(lines[0]).matches()) {
            return null;
        }
        // Find the start of the last item.
        int lastItem = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (LIST_ITEM.matcher(lines[i]).matches()) {
                lastItem = i;
                break;
            }
        }
        if (lastItem <= 0) {
            return null;
        }
        String kept = String.join("\n", List.of(lines).subList(0, lastItem)).strip();
        return kept.isEmpty() ? null : kept;
    }

    /** Lower-cased, whitespace-collapsed text for tolerant comparisons. */
    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
