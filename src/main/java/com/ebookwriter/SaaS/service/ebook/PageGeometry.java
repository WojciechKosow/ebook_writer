package com.ebookwriter.SaaS.service.ebook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The book's page geometry — trim size and margins — read from the
 * {@code @page} rule of {@code pdf/ebook.css}, the single source of truth the
 * renderer lays pages out with. Layout code that has to reason in absolute
 * lengths (table column sizing, the PDF inspector's content-area check) uses
 * this instead of repeating the numbers, so a change to the stylesheet's page
 * box is picked up everywhere.
 */
public record PageGeometry(float widthPt, float heightPt,
                           float marginTopPt, float marginRightPt, float marginBottomPt, float marginLeftPt) {

    /** What the stylesheet declares today; used if it can't be read. */
    static final PageGeometry FALLBACK = new PageGeometry(
            432f, 648f, cm(2f), cm(1.9f), cm(2.2f), cm(1.9f));

    private static final String CSS_PATH = "/pdf/ebook.css";
    private static final Pattern PAGE_RULE = Pattern.compile("@page\\s*\\{([^}]*)");
    private static final Pattern SIZE = Pattern.compile("(?:^|[;\\s])size\\s*:\\s*([^;]+);");
    private static final Pattern MARGIN = Pattern.compile("(?:^|[;\\s])margin\\s*:\\s*([^;]+);");
    private static final Pattern LENGTH = Pattern.compile("(-?[\\d.]+)(in|cm|mm|pt|px)?");

    private static volatile PageGeometry book;

    /** The geometry of the book's body pages (the default {@code @page} rule). */
    public static PageGeometry book() {
        PageGeometry g = book;
        if (g == null) {
            g = load();
            book = g;
        }
        return g;
    }

    /** Width of the text column: the page width less the side margins. */
    public float contentWidthPt() {
        return widthPt - marginLeftPt - marginRightPt;
    }

    static PageGeometry load() {
        try (InputStream in = PageGeometry.class.getResourceAsStream(CSS_PATH)) {
            if (in == null) {
                return FALLBACK;
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return FALLBACK;
        }
    }

    /** Parse the first bare {@code @page { size; margin }} rule; FALLBACK for anything missing. */
    static PageGeometry parse(String css) {
        Matcher rule = PAGE_RULE.matcher(css.replaceAll("/\\*.*?\\*/", ""));
        if (!rule.find()) {
            return FALLBACK;
        }
        String body = rule.group(1);
        float w = FALLBACK.widthPt;
        float h = FALLBACK.heightPt;
        Matcher size = SIZE.matcher(body);
        if (size.find()) {
            float[] v = lengths(size.group(1));
            if (v.length >= 2) {
                w = v[0];
                h = v[1];
            }
        }
        float[] m = {FALLBACK.marginTopPt, FALLBACK.marginRightPt, FALLBACK.marginBottomPt, FALLBACK.marginLeftPt};
        Matcher margin = MARGIN.matcher(body);
        if (margin.find()) {
            float[] v = lengths(margin.group(1));
            // CSS shorthand: 1, 2, 3 or 4 values (top, right, bottom, left).
            switch (v.length) {
                case 1 -> m = new float[]{v[0], v[0], v[0], v[0]};
                case 2 -> m = new float[]{v[0], v[1], v[0], v[1]};
                case 3 -> m = new float[]{v[0], v[1], v[2], v[1]};
                case 4 -> m = v;
                default -> { }
            }
        }
        return new PageGeometry(w, h, m[0], m[1], m[2], m[3]);
    }

    private static float[] lengths(String value) {
        Matcher l = LENGTH.matcher(value.strip());
        float[] out = new float[4];
        int n = 0;
        while (l.find() && n < 4) {
            float v = Float.parseFloat(l.group(1));
            String unit = l.group(2) == null ? "px" : l.group(2);
            out[n++] = switch (unit) {
                case "in" -> v * 72f;
                case "cm" -> cm(v);
                case "mm" -> cm(v / 10f);
                case "px" -> v * 0.75f;
                default -> v; // pt
            };
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static float cm(float v) {
        return v * 72f / 2.54f;
    }
}
