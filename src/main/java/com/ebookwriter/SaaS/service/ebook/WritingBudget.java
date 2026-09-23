package com.ebookwriter.SaaS.service.ebook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Credit-aware pacing for the writing phase. Before each chapter is written, the
 * orchestrator asks this class one question: <em>with the credits that remain,
 * can the rest of the planned book still be written — and if not, how does it end
 * naturally?</em>
 *
 * <p>The selected target length only shaped the plan. Here the only thing that
 * matters is the credit ceiling, and it acts as <b>permission</b>, not as a goal:
 * <ul>
 *   <li><b>Enough credits</b> — every remaining chapter is written at its planned
 *       depth. A chapter that ran long (the book is now past its soft target) is
 *       never cut; generation simply continues while credits allow.</li>
 *   <li><b>Slightly short</b> — the remaining chapters are written a little
 *       tighter (never below {@link #MIN_COMPRESSION} of their plan) so the whole
 *       planned book, including its designed ending, still fits.</li>
 *   <li><b>Genuinely short</b> — the book is <b>wound down</b>: as many upcoming
 *       chapters as fit are kept, the planned final chapter (the book's
 *       synthesis/next-step/closing) is always kept, and the chapters in between
 *       are <em>deferred</em> — left out cleanly, never half-written — so the book
 *       ends at a structural boundary with an authored ending, not where the
 *       credits happened to run out. Deferred chapters stay in the outline for a
 *       future "continue" feature.</li>
 * </ul>
 *
 * <p>Pure and static (no I/O), so every rule is unit-tested.
 */
public final class WritingBudget {

    private WritingBudget() {
    }

    /**
     * Fraction of the credit capacity actually planned against. Headings,
     * components, images and page breaks make real pages less dense than the
     * calibrated average, so a margin keeps the render inside the ceiling.
     */
    static final double SAFETY_FACTOR = 0.9;

    /** Remaining chapters may be tightened to this fraction of their plan before any is deferred. */
    static final double MIN_COMPRESSION = 0.6;

    /** The shortest a chapter is ever asked to be — below this it can't say anything useful. */
    static final int MIN_CHAPTER_WORDS = 300;

    /** One upcoming chapter: its number and planned length in words. */
    public record PlannedWords(int chapterNumber, int words) {
    }

    /**
     * The pacing decision for the rest of the book.
     *
     * @param targetWords word target per chapter still to write (in book order)
     * @param deferred    chapter numbers left out of this book to end it naturally
     * @param windDown    true when chapters were deferred (the ending was re-planned)
     * @param compressed  true when the remaining chapters were tightened to fit
     */
    public record Decision(Map<Integer, Integer> targetWords,
                           Set<Integer> deferred,
                           boolean windDown,
                           boolean compressed) {

        public int targetFor(int chapterNumber) {
            return targetWords.getOrDefault(chapterNumber, MIN_CHAPTER_WORDS);
        }

        public boolean isDeferred(int chapterNumber) {
            return deferred.contains(chapterNumber);
        }
    }

    /**
     * Words the credit ceiling can hold, net of front matter, a page per possible
     * full-page opener, image slack and the safety margin.
     *
     * @param pageBudget    the reserved credit ceiling (pages), {@code <= 0} for none
     * @param chapterCount  planned chapters (each may get a full-page opener)
     * @param reservedPages extra pages to hold back (e.g. for generated images)
     * @return the word capacity, or {@link Integer#MAX_VALUE} when there is no ceiling
     */
    public static int capacityWords(int pageBudget, int chapterCount, int reservedPages) {
        if (pageBudget <= 0) {
            return Integer.MAX_VALUE;
        }
        int contentPages = pageBudget - EbookHtmlBuilder.FRONT_MATTER_PAGES
                - Math.max(0, chapterCount) - Math.max(0, reservedPages);
        return (int) Math.max(0, Math.floor(contentPages * ChapterGenerationService.WORDS_PER_PAGE * SAFETY_FACTOR));
    }

    /**
     * Decide how to write the remaining chapters.
     *
     * @param remaining    the chapters not yet written, in book order; the last one
     *                     is the book's planned ending
     * @param wordsWritten words already written in earlier chapters
     * @param capacity     total word capacity from {@link #capacityWords}
     */
    public static Decision decide(List<PlannedWords> remaining, int wordsWritten, int capacity) {
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        if (remaining.isEmpty()) {
            return new Decision(targets, Set.of(), false, false);
        }
        long left = capacity == Integer.MAX_VALUE ? Long.MAX_VALUE : (long) capacity - Math.max(0, wordsWritten);
        long planned = remaining.stream().mapToLong(p -> Math.max(MIN_CHAPTER_WORDS, p.words())).sum();

        // 1. Everything fits: write the book as planned.
        if (planned <= left) {
            remaining.forEach(p -> targets.put(p.chapterNumber(), Math.max(MIN_CHAPTER_WORDS, p.words())));
            return new Decision(targets, Set.of(), false, false);
        }

        // 2. Slightly short: tighten every remaining chapter proportionally.
        double factor = left <= 0 ? 0 : (double) left / planned;
        if (factor >= MIN_COMPRESSION) {
            remaining.forEach(p -> targets.put(p.chapterNumber(),
                    Math.max(MIN_CHAPTER_WORDS, (int) Math.floor(Math.max(MIN_CHAPTER_WORDS, p.words()) * factor))));
            return new Decision(targets, Set.of(), false, true);
        }

        // 3. Genuinely short: wind down. The planned ending is always kept (it is
        // where the synthesis, next step and closing live); keep as many upcoming
        // chapters as fit at a tightened depth, and defer the ones in between.
        PlannedWords ending = remaining.get(remaining.size() - 1);
        List<PlannedWords> body = remaining.subList(0, remaining.size() - 1);

        long budgetLeft = Math.max(0, left);
        int endingWords = (int) Math.max(MIN_CHAPTER_WORDS,
                Math.min(Math.max(MIN_CHAPTER_WORDS, ending.words()), budgetLeft / 3));
        long forBody = budgetLeft - endingWords;

        List<PlannedWords> kept = new ArrayList<>();
        Set<Integer> deferred = new LinkedHashSet<>();
        long used = 0;
        boolean stop = false;
        for (PlannedWords p : body) {
            int words = Math.max(MIN_CHAPTER_WORDS,
                    (int) Math.floor(Math.max(MIN_CHAPTER_WORDS, p.words()) * MIN_COMPRESSION));
            // Keep chapters in order until one no longer fits; everything after it
            // is deferred too, so the book never skips a chapter and resumes later.
            if (!stop && used + words <= forBody) {
                kept.add(new PlannedWords(p.chapterNumber(), words));
                used += words;
            } else {
                stop = true;
                deferred.add(p.chapterNumber());
            }
        }
        // Give the ending any slack the kept chapters didn't use.
        endingWords = (int) Math.max(endingWords, Math.min(Math.max(MIN_CHAPTER_WORDS, ending.words()),
                budgetLeft - used));

        kept.forEach(p -> targets.put(p.chapterNumber(), p.words()));
        targets.put(ending.chapterNumber(), endingWords);
        return new Decision(targets, Set.copyOf(deferred), !deferred.isEmpty(), true);
    }
}
