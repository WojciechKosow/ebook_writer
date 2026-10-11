package com.ebookwriter.SaaS.service.ebook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The book template: the recurring sections every chapter ends with (a
 * practice section, a recap, …), decided <b>once for the whole book</b> — never
 * chapter by chapter. A section in the template is in every chapter, with the
 * same heading; a section not in it is in none. An empty template (a book with
 * no recurring sections) is a real decision, not a missing one.
 *
 * <p>Nothing here names a genre: the planner decides which sections, if any,
 * suit the book, and in the book's own language.
 *
 * @param sections the recurring sections, in the order they close a chapter
 */
public record BookTemplate(List<Section> sections) {

    /** At most this many recurring sections per chapter. */
    public static final int MAX_SECTIONS = 3;
    static final int MAX_HEADING_CHARS = 80;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One recurring section: its exact heading and what it holds (internal guidance for the writer). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String heading, String purpose) {
    }

    public BookTemplate {
        List<Section> clean = new ArrayList<>();
        if (sections != null) {
            for (Section s : sections) {
                if (s == null || s.heading() == null || s.heading().isBlank()
                        || s.heading().strip().length() > MAX_HEADING_CHARS) {
                    continue;
                }
                String heading = s.heading().strip().replaceFirst("^#+\\s*", "");
                boolean dup = clean.stream().anyMatch(c -> normalise(c.heading()).equals(normalise(heading)));
                if (!dup && clean.size() < MAX_SECTIONS) {
                    clean.add(new Section(heading, s.purpose() == null ? "" : s.purpose().strip()));
                }
            }
        }
        sections = List.copyOf(clean);
    }

    /** A book with no recurring sections. */
    public static BookTemplate none() {
        return new BookTemplate(List.of());
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(sections);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** The stored template, or null when the book never had one decided (older books). */
    public static BookTemplate fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new BookTemplate(MAPPER.readValue(json, new TypeReference<List<Section>>() { }));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The template sections whose heading the chapter does not have as a
     * section heading ({@code ##}/{@code ###}), compared case- and
     * punctuation-insensitively.
     */
    public List<Section> missingFrom(String markdown) {
        List<String> headings = new ArrayList<>();
        if (markdown != null) {
            for (String line : markdown.split("\n")) {
                String t = line.strip();
                if (t.matches("^#{2,3}\\s+.*")) {
                    headings.add(normalise(t.replaceFirst("^#+\\s*", "")));
                }
            }
        }
        List<Section> missing = new ArrayList<>();
        for (Section s : sections) {
            if (!headings.contains(normalise(s.heading()))) {
                missing.add(s);
            }
        }
        return missing;
    }

    /** Lower-cased letters and digits only, for comparing headings. */
    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
