package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detecting a promised fixed structure ("7-day plan", "10-step guide") is what
 * lets the pipeline insist the finished book actually delivers every unit.
 */
class StructureRequirementTest {

    @Test
    void detectsHyphenatedDayCountFromTitle() {
        Optional<StructureRequirement> r = StructureRequirement.detect("The 7-Day Focus Reset");
        assertTrue(r.isPresent());
        assertEquals(7, r.get().count());
        assertEquals("day", r.get().unit());
        assertEquals("7 days", r.get().describe());
    }

    @Test
    void detectsVariousUnits() {
        assertEquals("step", StructureRequirement.detect("A 10-step guide to calm").orElseThrow().unit());
        assertEquals(30, StructureRequirement.detect("The 30 Day Challenge").orElseThrow().count());
        assertEquals("principle", StructureRequirement.detect("5 principles of focus").orElseThrow().unit());
        assertEquals("week", StructureRequirement.detect("An 8-week program").orElseThrow().unit());
    }

    @Test
    void singularisesPlurals() {
        assertEquals("strategy", StructureRequirement.detect("12 strategies for sleep").orElseThrow().unit());
        assertEquals("habit", StructureRequirement.detect("7 habits that stick").orElseThrow().unit());
    }

    @Test
    void ignoresNonStructuralNumbersAndOutOfRangeCounts() {
        assertTrue(StructureRequirement.detect("Marketing in 2024").isEmpty(), "a year is not a structure");
        assertTrue(StructureRequirement.detect("A guide to focus").isEmpty(), "no number, no structure");
        assertTrue(StructureRequirement.detect("The 1-day nap").isEmpty(), "a single unit is not a fixed series");
        assertTrue(StructureRequirement.detect("500 steps to nowhere").isEmpty(), "absurd counts are ignored");
    }

    @Test
    void prefersTitleThenTopicThenInstructions() {
        Ebook ebook = Ebook.builder()
                .title("The 7-Day Focus Reset")
                .topic("How to stop being distracted in 30 days")
                .additionalInstructions("make it a 5-part series")
                .build();
        StructureRequirement r = StructureRequirement.detect(ebook).orElseThrow();
        assertEquals(7, r.count(), "the title's promise wins");
        assertEquals("day", r.unit());
    }

    @Test
    void fallsBackToTopicWhenTitleHasNoStructure() {
        Ebook ebook = Ebook.builder()
                .title("Focus Reset")
                .topic("A 21-day plan to reclaim your attention")
                .build();
        StructureRequirement r = StructureRequirement.detect(ebook).orElseThrow();
        assertEquals(21, r.count());
        assertEquals("day", r.unit());
    }
}
