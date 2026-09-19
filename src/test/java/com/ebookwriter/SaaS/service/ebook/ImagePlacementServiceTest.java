package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placement turns a semantic anchor (chapter + optional heading) into an inline
 * {@code ![alt](ebook-image:<id>)} token in the Markdown — never coordinates.
 */
class ImagePlacementServiceTest {

    private static final String REF = "ebook-image:abc";

    @Test
    void insertsAfterMatchingHeadingBlock() {
        String md = """
                Opening paragraph.

                ## How compound interest works

                The balance grows each period.

                ## Next section

                More text.""";

        String out = ImagePlacementService.insertReference(md, REF, "How compound interest works", "A chart");

        // Token sits directly after the matched heading, before that section's body.
        int heading = out.indexOf("## How compound interest works");
        int token = out.indexOf("![A chart](" + REF + ")");
        int body = out.indexOf("The balance grows");
        assertTrue(heading >= 0 && token > heading && body > token,
                "image must be inserted between the matched heading and its body");
    }

    @Test
    void fallsBackToAfterFirstBlockWhenHeadingNotFound() {
        String md = """
                Opening paragraph.

                Second paragraph.""";

        String out = ImagePlacementService.insertReference(md, REF, "No such heading", "alt");

        int first = out.indexOf("Opening paragraph.");
        int token = out.indexOf("![alt](" + REF + ")");
        int second = out.indexOf("Second paragraph.");
        assertTrue(first >= 0 && token > first && second > token,
                "with no heading match the image goes after the first block");
    }

    @Test
    void nullAnchorPlacesAfterFirstBlock() {
        String out = ImagePlacementService.insertReference("Intro.\n\nRest.", REF, null, "alt");
        assertTrue(out.indexOf("![alt](" + REF + ")") > out.indexOf("Intro."));
        assertTrue(out.indexOf("Rest.") > out.indexOf(REF));
    }

    @Test
    void blankContentYieldsJustTheImageBlock() {
        assertEquals("![alt](" + REF + ")",
                ImagePlacementService.insertReference("", REF, "x", "alt"));
        assertEquals("![alt](" + REF + ")",
                ImagePlacementService.insertReference(null, REF, "x", "alt"));
    }

    @Test
    void sanitizesAltTextSoTheTokenStaysValid() {
        String out = ImagePlacementService.insertReference("Body.", REF, null, "a ] bad\nalt");
        assertTrue(out.contains("![a  bad alt](" + REF + ")"), out);
        assertFalse(out.contains("] bad"));
    }
}
