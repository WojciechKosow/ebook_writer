package com.ebookwriter.SaaS.service.ebook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Book-level state for one render of the manuscript: which chapter is being
 * rendered, which blocks the book has already shown (so a block is printed only
 * once in the whole book), and the notes the renderer leaves about what it had
 * to correct or could not fit. The notes feed the author's quality report
 * ({@link BookQualityInspector}); the renderer never needs them itself.
 *
 * <p>One context spans one book build. {@link EbookContentRenderer#toHtml(String)}
 * without a context uses a fresh one, so a single chapter renders as before.
 */
public final class RenderContext {

    /** What the renderer corrected or flagged. */
    public enum NoteType {
        /** A heading with no content under it was not printed. */
        EMPTY_HEADING_REMOVED,
        /** A block already printed earlier in the book was not printed again. */
        DUPLICATE_REMOVED,
        /** A verbatim block printed twice (kept: a repeated listing may be intended). */
        DUPLICATE_VERBATIM,
        /** A verbatim block was set smaller so its longest line fits the column. */
        VERBATIM_SCALED,
        /** A verbatim line did not fit even at the minimum size and was carried over visibly. */
        VERBATIM_WRAPPED
    }

    /**
     * One note. {@code qaRef} is the {@link #QA_ATTR} value stamped on the element
     * concerned (null when the element was removed), so the inspector can find
     * the page it landed on.
     */
    public record Note(NoteType type, int chapter, String message, String excerpt, String qaRef) {
    }

    /** Attribute that ties an element in the rendered book to a {@link Note}. */
    static final String QA_ATTR = "data-qa";

    private final Map<String, Integer> seenBlocks = new HashMap<>();
    private final List<Note> notes = new ArrayList<>();
    private int chapter;
    private int refs;

    /** Mark the chapter being rendered (its chapter number); notes are attributed to it. */
    public void startChapter(int chapterNumber) {
        this.chapter = chapterNumber;
    }

    public int chapter() {
        return chapter;
    }

    public List<Note> notes() {
        return List.copyOf(notes);
    }

    /**
     * Record that a block with this normalised fingerprint is printed in the
     * current chapter. Returns the chapter that already printed it, or -1 if
     * this is its first appearance in the book.
     */
    int firstSeen(String fingerprint) {
        Integer first = seenBlocks.putIfAbsent(fingerprint, chapter);
        return first == null ? -1 : first;
    }

    /** A fresh reference value for {@link #QA_ATTR}. */
    String newRef() {
        return "q" + (++refs);
    }

    void note(NoteType type, String message, String excerpt, String qaRef) {
        notes.add(new Note(type, chapter, message, excerpt, qaRef));
    }
}
