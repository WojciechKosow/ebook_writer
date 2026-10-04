package com.ebookwriter.SaaS.service.ebook;

import com.openhtmltopdf.extend.FSTextBreaker;
import com.openhtmltopdf.layout.UrlAwareLineBreakIterator;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The renderer's own line breaker (URL-aware: it wraps after {@code .}, {@code -}
 * and {@code /}), plus one addition: a break opportunity after every zero-width
 * space. The renderer's breaker ignores U+200B, so without this a ZWSP is no
 * break at all; a soft hyphen would break, but prints a hyphen at the break,
 * which in code ({@code OFFER_NOT_-FOUND}) reads as part of the identifier.
 *
 * <p>ZWSPs are only ever inserted by {@link TableLayout} into code inside table
 * cells, so every other piece of text wraps exactly as it did before.
 */
final class CodeBreakLineBreaker implements FSTextBreaker {

    static final char ZWSP = '​';

    private final FSTextBreaker delegate = defaultBreaker();
    private int[] breaks = new int[0];
    private int index;

    /** The breaker openhtmltopdf uses when none is supplied. */
    static FSTextBreaker defaultBreaker() {
        return new UrlAwareLineBreakIterator(BreakIterator.getLineInstance(Locale.US));
    }

    @Override
    public void setText(String text) {
        delegate.setText(text);
        index = 0;
        if (text.indexOf(ZWSP) < 0) {
            breaks = null; // fast path: plain delegation
            return;
        }
        TreeSet<Integer> all = new TreeSet<>();
        for (int b = delegate.next(); b != BreakIterator.DONE; b = delegate.next()) {
            all.add(b);
        }
        for (int i = text.indexOf(ZWSP); i >= 0; i = text.indexOf(ZWSP, i + 1)) {
            all.add(i + 1);
        }
        breaks = all.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public int next() {
        if (breaks == null) {
            return delegate.next();
        }
        return index < breaks.length ? breaks[index++] : BreakIterator.DONE;
    }
}
