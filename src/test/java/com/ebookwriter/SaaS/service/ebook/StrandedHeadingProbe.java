package com.ebookwriter.SaaS.service.ebook;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a rendered book back and finds <b>stranded headings</b>: a section
 * heading (h2/h3/h4, recognised by the stylesheet's sans face and size) that
 * ends a page with fewer than {@link #MIN_LINES_AFTER} lines of its section
 * under it — the reader turns the page to find what the heading introduces.
 */
final class StrandedHeadingProbe {

    /** A heading needs at least this many lines of its own content on its page. */
    static final int MIN_LINES_AFTER = 2;

    record Line(int page, float y, String font, float size, String text) {
        boolean sans() {
            return font.contains("Sans");
        }

        boolean bold() {
            return font.contains("Bold");
        }

        /**
         * h2 (15.5pt) and h3 (12.5pt) are bold sans; h4 is 9.5pt uppercase sans.
         * PDFBox reports the size floored to whole points (15, 12, 9).
         */
        boolean heading() {
            if (!sans() || text.strip().matches("\\d+")) {
                return false; // folios, step indexes ("01") and TOC numbers are not headings
            }
            if (bold() && size >= 12f && size < 17f) {
                return true;
            }
            return !bold() && size >= 9f && size < 10f
                    && text.equals(text.toUpperCase()) && text.chars().anyMatch(Character::isLetter);
        }

        /**
         * Any title-like line: a heading, a component label or table header
         * (8.5pt bold sans capitals), a bold label on its own ("Request"), or a
         * short lead-in ending in a colon ("Your task:").
         */
        boolean title() {
            if (heading()) {
                return true;
            }
            String t = text.strip();
            if (sans() && bold() && size < 9f && t.equals(t.toUpperCase()) && t.chars().anyMatch(Character::isLetter)) {
                return true;
            }
            if (!sans() && bold() && t.length() <= 40 && !t.contains(" — ") && !t.contains("—")) {
                return true;
            }
            return !sans() && !font.contains("Mono") && t.endsWith(":") && t.length() <= 60;
        }

        /** The page number at the foot of a page. */
        boolean folio() {
            return sans() && size < 9f && text.strip().matches("\\d+");
        }
    }

    record Stranded(int page, String heading, int linesAfter) {
    }

    private StrandedHeadingProbe() {
    }

    static List<Line> lines(byte[] pdf) throws IOException {
        List<Line> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (positions.isEmpty() || text.isBlank()) {
                        return;
                    }
                    TextPosition first = positions.get(0);
                    String font = first.getFont() == null ? "" : String.valueOf(first.getFont().getName());
                    out.add(new Line(getCurrentPageNo(), first.getYDirAdj(), font,
                            first.getFontSizeInPt(), text.strip()));
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return out;
    }

    /** Every heading that ends its page with too little of its section beneath it. */
    static List<Stranded> find(byte[] pdf) throws IOException {
        return find(pdf, Line::heading);
    }

    /** Every title of any kind ({@link Line#title}) that ends its page with too little beneath it. */
    static List<Stranded> findTitles(byte[] pdf) throws IOException {
        return find(pdf, Line::title);
    }

    private static List<Stranded> find(byte[] pdf, java.util.function.Predicate<Line> isTitle) throws IOException {
        List<Line> all = lines(pdf);
        List<Stranded> found = new ArrayList<>();
        int pages = all.isEmpty() ? 0 : all.get(all.size() - 1).page();
        for (int p = 2; p <= pages; p++) { // page 1 is the cover
            List<Line> page = new ArrayList<>();
            for (Line l : all) {
                if (l.page() == p && !l.folio()) {
                    page.add(l);
                }
            }
            // Walk from the foot upwards: the last heading on the page and how many
            // distinct lines (by baseline) sit below it.
            int lastHeading = -1;
            for (int i = page.size() - 1; i >= 0; i--) {
                if (isTitle.test(page.get(i))) {
                    lastHeading = i;
                    break;
                }
            }
            if (lastHeading < 0 || p == pages) {
                continue;
            }
            float headingY = page.get(lastHeading).y();
            List<Float> baselines = new ArrayList<>();
            for (int i = lastHeading + 1; i < page.size(); i++) {
                Line l = page.get(i);
                if (isTitle.test(l) || l.y() <= headingY + 1f) {
                    continue;
                }
                if (baselines.stream().noneMatch(b -> Math.abs(b - l.y()) < 1f)) {
                    baselines.add(l.y());
                }
            }
            if (baselines.size() < MIN_LINES_AFTER) {
                found.add(new Stranded(p, page.get(lastHeading).text(), baselines.size()));
            }
        }
        return found;
    }
}
