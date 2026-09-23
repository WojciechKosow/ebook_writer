package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chapter must always end at a semantic boundary — never mid-thought. */
class ManuscriptIntegrityTest {

    @Test
    void repairsAChapterCutOffMidSentence() {
        String cut = "## Why it works\n\nAttention is a budget.\n\nWhen you protect the first hour, the rest of";
        String repaired = ManuscriptIntegrity.repairTruncated(cut);

        assertEquals("## Why it works\n\nAttention is a budget.", repaired);
        assertFalse(ManuscriptIntegrity.endsMidThought(repaired));
    }

    @Test
    void dropsAnUnclosedExerciseInsteadOfShippingHalfOfIt() {
        String cut = "Intro paragraph.\n\n:::exercise The audit\nList every app you opened today.\n\nThen mark";
        assertEquals("Intro paragraph.", ManuscriptIntegrity.repairTruncated(cut));
    }

    @Test
    void keepsAClosedComponentThatContainsBlankLines() {
        String ok = "Intro.\n\n:::exercise Audit\nStep one.\n\nStep two.\n:::";
        assertEquals(ok, ManuscriptIntegrity.repairTruncated(ok));
    }

    @Test
    void dropsAnUnterminatedCodeFence() {
        String cut = "Setup.\n\n```java\nclass A {";
        assertEquals("Setup.", ManuscriptIntegrity.repairTruncated(cut));
    }

    @Test
    void keepsCompleteListItemsAndDropsOnlyTheUnfinishedOne() {
        String cut = "Do this:\n\n- Silence notifications.\n- Close the inbox.\n- Put the phone in";
        assertEquals("Do this:\n\n- Silence notifications.\n- Close the inbox.",
                ManuscriptIntegrity.repairTruncated(cut));
    }

    @Test
    void aChapterNeverEndsOnAnOrphanHeading() {
        String text = "Body text.\n\n## Final thoughts";
        assertEquals("Body text.", ManuscriptIntegrity.trimTrailingOrphans(text));
        assertEquals("Body text.", ManuscriptIntegrity.repairTruncated(text));
    }

    @Test
    void leavesCleanContentUntouched() {
        String clean = "## Section\n\nA complete thought.\n\n| a | b |\n|---|---|\n| 1 | 2 |";
        assertEquals(clean, ManuscriptIntegrity.repairTruncated(clean));
    }

    @Test
    void detectsADanglingForwardReferenceOnlyInTheFinalParagraph() {
        assertTrue(ManuscriptIntegrity.hasDanglingForwardReference(
                "Good work.\n\nIn the next chapter, we will build your evening routine."));
        assertFalse(ManuscriptIntegrity.hasDanglingForwardReference(
                "In the next section we look at mornings.\n\n## Mornings\n\nStart before the phone."));
        assertFalse(ManuscriptIntegrity.hasDanglingForwardReference(
                "Focus is a practice. Begin tomorrow, and keep the first hour yours."));
    }

    @Test
    void listsWithoutPeriodsAreNotFlaggedAsMidThought() {
        assertFalse(ManuscriptIntegrity.endsMidThought("Checklist:\n\n- Phone away\n- Timer set"));
    }
}
