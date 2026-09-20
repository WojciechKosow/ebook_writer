package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A <em>promised structure</em> inferred from the brief — e.g. a "7-day plan", a
 * "10-step guide", a "30-day challenge", a "5 principles" book. When the user
 * asks for a fixed-count structure, the finished book must actually deliver every
 * one of those units (all seven days, all ten steps); stopping partway is the
 * single most damaging content bug.
 *
 * <p>Detection is deliberately conservative: it only fires on an explicit numeral
 * immediately qualifying a known structural unit, in the title, topic, or
 * instructions. It feeds the planning prompt (so the outline covers every unit)
 * and a post-plan sanity check.
 *
 * @param count the promised number of units (e.g. 7)
 * @param unit  the singular unit noun, lower-cased (e.g. "day", "step")
 */
public record StructureRequirement(int count, String unit) {

    /** Structural unit nouns we recognise, as a single alternation (plural-aware). */
    private static final Pattern PATTERN = Pattern.compile(
            "(\\d{1,3})\\s*[-–]?\\s*"
                    + "(day|week|month|step|stage|phase|part|chapter|lesson|module|"
                    + "principle|rule|law|habit|secret|key|pillar|question|mistake|"
                    + "myth|strategy|strategie|tactic|technique|practice|session|"
                    + "exercise|workout|recipe|prompt)s?\\b",
            Pattern.CASE_INSENSITIVE);

    /** Lower/upper bounds that keep matches sane (a "100-day" course is fine; "2024" is not). */
    private static final int MIN_COUNT = 2;
    private static final int MAX_COUNT = 60;

    /**
     * Detect a promised structure from the book's brief. Title is checked first
     * (it is where the promise usually lives — "The 7-Day Focus Reset"), then the
     * topic, then any additional instructions. Returns empty when nothing explicit
     * is found — most books have no fixed count and must not be forced into one.
     */
    public static Optional<StructureRequirement> detect(Ebook ebook) {
        if (ebook == null) {
            return Optional.empty();
        }
        return detect(ebook.getTitle())
                .or(() -> detect(ebook.getTopic()))
                .or(() -> detect(ebook.getAdditionalInstructions()));
    }

    /** Detect a promised structure in a single piece of text. */
    public static Optional<StructureRequirement> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(text);
        while (m.find()) {
            int count;
            try {
                count = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (count < MIN_COUNT || count > MAX_COUNT) {
                continue;
            }
            return Optional.of(new StructureRequirement(count, singular(m.group(2).toLowerCase())));
        }
        return Optional.empty();
    }

    /** Normalise the odd irregular stem the alternation allows. */
    private static String singular(String unit) {
        return "strategie".equals(unit) ? "strategy" : unit;
    }

    /** e.g. {@code "7 days"} — for embedding in prompts and log lines. */
    public String describe() {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
