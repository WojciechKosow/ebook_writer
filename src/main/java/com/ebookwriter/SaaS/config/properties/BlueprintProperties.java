package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for the Book Blueprint stage (BookKnowledge → OpenAI → blueprint +
 * questions). They keep the questions short ("a few details", never a form),
 * the structure sane, and the cost of (re)building bounded.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "blueprint")
public class BlueprintProperties {

    /** Most questions asked per blueprint — the model is told to prioritise; this enforces it. */
    private int maxQuestions = 8;

    /** Chapters kept from the model / allowed after the author's edits. */
    private int maxChapters = 30;

    /** How many times one book's blueprint may be (re)built with OpenAI. */
    private int maxGenerations = 10;

    /** BookKnowledge JSON sent to the planner (chars); larger knowledge is trimmed per list. */
    private int maxKnowledgeChars = 120_000;

    /** A build stuck in BUILDING_BLUEPRINT longer than this may be restarted (minutes). */
    private int staleBuildingMinutes = 30;

    /** Longest answer the author may give to one question (chars). */
    private int maxAnswerChars = 5_000;
}
