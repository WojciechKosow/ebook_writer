package com.ebookwriter.SaaS.service.ebook;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a rendered book back and checks how its tables break across pages. The
 * tables it inspects are built by {@link #table}: every row starts with a
 * marker {@code t<T>r<R>a} in its first cell and ends with {@code t<T>r<R>z} in
 * its last, and the header's first cell reads {@code KEY<T>}, so the probe can
 * tell from the text layer alone where each row and header landed.
 */
final class TablePaginationProbe {

    private static final Pattern ROW_START = Pattern.compile("t(\\d+)r(\\d+)a");
    private static final Pattern ROW_END = Pattern.compile("t(\\d+)r(\\d+)z");
    private static final Pattern HEADER = Pattern.compile("KEY(\\d+)");
    private static final Pattern BEFORE = Pattern.compile("before(\\d+)\\.");

    private TablePaginationProbe() {
    }

    /**
     * A probe table: a header, then rows whose middle cell holds {@code words[r]}
     * words and ends with the row's end marker (so the marker sits on the row's
     * last line). The text before the table should end with {@link #before}.
     */
    static String table(int t, int[] words) {
        StringBuilder sb = new StringBuilder("| Key" + t + " | Description | Note |\n|---|---|---|\n");
        for (int r = 0; r < words.length; r++) {
            sb.append("| t").append(t).append('r').append(r).append("a | ")
                    .append("word ".repeat(Math.max(1, words[r])))
                    .append('t').append(t).append('r').append(r).append("z | n |\n");
        }
        return sb.toString();
    }

    /** A paragraph marking the content just before table {@code t}. */
    static String before(int t) {
        return "Filler before" + t + ".";
    }

    static int[] uniform(int rows, int words) {
        int[] w = new int[rows];
        java.util.Arrays.fill(w, words);
        return w;
    }

    record Report(List<String> strandedHeaders, List<String> missingRepeatHeaders, List<String> splitRows,
                  List<String> gaps, int continuations) {
        boolean clean() {
            return strandedHeaders.isEmpty() && missingRepeatHeaders.isEmpty() && splitRows.isEmpty() && gaps.isEmpty();
        }
    }

    /**
     * @param gapLimitPt a page that is followed by more of the same table may leave
     *                   at most this much empty space at its foot (0 to skip)
     */
    static Report inspect(byte[] pdf, float gapLimitPt) throws IOException {
        List<StrandedHeadingProbe.Line> lines = StrandedHeadingProbe.lines(pdf);
        // table -> row -> page of its start/end marker; table -> pages showing its header
        Map<Integer, TreeMap<Integer, Integer>> startPage = new TreeMap<>();
        Map<Integer, TreeMap<Integer, Integer>> endPage = new TreeMap<>();
        Map<Integer, List<Integer>> headerPages = new TreeMap<>();
        Map<Integer, Float> lowestOnPage = new LinkedHashMap<>();
        Map<Integer, Integer> beforePage = new TreeMap<>();
        for (StrandedHeadingProbe.Line l : lines) {
            if (!l.folio()) {
                lowestOnPage.merge(l.page(), l.y(), Math::max);
            }
            Matcher m = ROW_START.matcher(l.text());
            while (m.find()) {
                startPage.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new TreeMap<>())
                        .put(Integer.parseInt(m.group(2)), l.page());
            }
            m = ROW_END.matcher(l.text());
            while (m.find()) {
                endPage.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new TreeMap<>())
                        .put(Integer.parseInt(m.group(2)), l.page());
            }
            m = BEFORE.matcher(l.text());
            while (m.find()) {
                beforePage.put(Integer.parseInt(m.group(1)), l.page());
            }
            m = HEADER.matcher(l.text());
            while (m.find()) {
                headerPages.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new ArrayList<>()).add(l.page());
            }
        }
        List<String> stranded = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> split = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int continuations = 0;
        for (var e : startPage.entrySet()) {
            int t = e.getKey();
            TreeMap<Integer, Integer> rows = e.getValue();
            List<Integer> headers = headerPages.getOrDefault(t, List.of());
            int firstRowPage = rows.firstEntry().getValue();
            if (!headers.isEmpty() && headers.get(0) < firstRowPage) {
                stranded.add("table " + t + ": header alone on p" + headers.get(0));
            }
            Integer prev = beforePage.get(t);
            if (gapLimitPt > 0 && prev != null && prev < firstRowPage) {
                float pageBottom = PageGeometry.book().heightPt() - PageGeometry.book().marginBottomPt();
                float gap = pageBottom - lowestOnPage.getOrDefault(prev, pageBottom);
                if (gap > gapLimitPt) {
                    gaps.add(String.format("table %d: p%d leaves %.0fpt empty before the table starts", t, prev, gap));
                }
            }
            java.util.Set<Integer> rowPages = new java.util.TreeSet<>(rows.values());
            for (int p : rowPages) {
                if (p != firstRowPage) {
                    continuations++;
                    if (!headers.contains(p)) {
                        missing.add("table " + t + ": p" + p + " continues without its header");
                    }
                    if (gapLimitPt > 0) {
                        float pageBottom = PageGeometry.book().heightPt() - PageGeometry.book().marginBottomPt();
                        float gap = pageBottom - lowestOnPage.getOrDefault(p - 1, pageBottom);
                        if (gap > gapLimitPt) {
                            gaps.add(String.format("table %d: p%d leaves %.0fpt empty before the table continues", t, p - 1, gap));
                        }
                    }
                }
            }
            for (var r : rows.entrySet()) {
                Integer end = endPage.getOrDefault(t, new TreeMap<>()).get(r.getKey());
                if (end != null && !end.equals(r.getValue())) {
                    split.add("table " + t + " row " + r.getKey() + ": p" + r.getValue() + "->p" + end);
                }
            }
        }
        return new Report(stranded, missing, split, gaps, continuations);
    }
}
