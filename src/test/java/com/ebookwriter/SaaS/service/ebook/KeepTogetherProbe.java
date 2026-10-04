package com.ebookwriter.SaaS.service.ebook;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a rendered book back and checks logical blocks from their markers: the
 * title of block {@code n} (a heading, label, exercise or callout title)
 * carries {@code K<n>K}, the first line of its content carries {@code S<n>S},
 * and the paragraph just before the block reads {@code before<n>.}. A block is
 * <b>orphaned</b> when its title ends a page and its content begins on a later
 * one; when a block moved to a new page, the probe also reports how much space
 * it left behind.
 */
final class KeepTogetherProbe {

    private static final Pattern TITLE = Pattern.compile("K(\\d+)K");
    private static final Pattern START = Pattern.compile("S(\\d+)S");
    private static final Pattern BEFORE = Pattern.compile("before(\\d+)\\.");
    private static final Pattern END = Pattern.compile("E(\\d+)E");

    private KeepTogetherProbe() {
    }

    static String title(int n) {
        return "K" + n + "K";
    }

    static String start(int n) {
        return "S" + n + "S";
    }

    /** Marks the last line of block {@code n}'s content. */
    static String end(int n) {
        return "E" + n + "E";
    }

    static String before(int n) {
        return "Text before" + n + ".";
    }

    /** Where one block landed. {@code gapLeft} is the empty space left on the page it moved away from (0 if it didn't). */
    record Placement(int block, int titlePage, int startPage, int beforePage, float gapLeft, int endPage) {
        boolean orphaned() {
            return titlePage > 0 && startPage > titlePage;
        }

        boolean moved() {
            return beforePage > 0 && titlePage > beforePage;
        }
    }

    static Map<Integer, Placement> inspect(byte[] pdf) throws IOException {
        List<StrandedHeadingProbe.Line> lines = StrandedHeadingProbe.lines(pdf);
        Map<Integer, Integer> title = new HashMap<>();
        Map<Integer, Integer> start = new HashMap<>();
        Map<Integer, Integer> before = new HashMap<>();
        Map<Integer, Float> lowest = new HashMap<>();
        Map<Integer, Integer> end = new HashMap<>();
        for (StrandedHeadingProbe.Line l : lines) {
            if (!l.folio()) {
                lowest.merge(l.page(), l.y(), Math::max);
            }
            collect(TITLE.matcher(l.text()), l.page(), title);
            collect(START.matcher(l.text()), l.page(), start);
            collect(BEFORE.matcher(l.text()), l.page(), before);
            collect(END.matcher(l.text()), l.page(), end);
        }
        float bottom = PageGeometry.book().heightPt() - PageGeometry.book().marginBottomPt();
        Map<Integer, Placement> out = new LinkedHashMap<>();
        for (int n : new java.util.TreeSet<>(title.keySet())) {
            int t = title.get(n);
            int b = before.getOrDefault(n, 0);
            float gap = b > 0 && t > b ? bottom - lowest.getOrDefault(b, bottom) : 0;
            out.put(n, new Placement(n, t, start.getOrDefault(n, 0), b, gap, end.getOrDefault(n, 0)));
        }
        return out;
    }

    static List<Placement> orphans(byte[] pdf) throws IOException {
        List<Placement> out = new ArrayList<>();
        for (Placement p : inspect(pdf).values()) {
            if (p.orphaned()) {
                out.add(p);
            }
        }
        return out;
    }

    private static void collect(Matcher m, int page, Map<Integer, Integer> into) {
        while (m.find()) {
            into.putIfAbsent(Integer.parseInt(m.group(1)), page);
        }
    }
}
