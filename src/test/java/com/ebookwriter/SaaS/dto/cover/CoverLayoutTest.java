package com.ebookwriter.SaaS.dto.cover;

import com.ebookwriter.SaaS.dto.image.AspectRatio;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each visual layout fixes the aspect ratio its cover image is generated at, so
 * the visual fills its CSS region without distortion. The full-bleed layout is
 * portrait; the partial-region layouts are landscape; the typographic layout has
 * no image region at all.
 */
class CoverLayoutTest {

    @Test
    void fullBleedLayoutIsPortrait() {
        assertEquals(AspectRatio.PORTRAIT, CoverLayout.IMAGE_LED.imageAspectRatio());
    }

    @Test
    void partialRegionLayoutsAreLandscape() {
        assertEquals(AspectRatio.LANDSCAPE, CoverLayout.EDITORIAL.imageAspectRatio());
        assertEquals(AspectRatio.LANDSCAPE, CoverLayout.SPLIT.imageAspectRatio());
        assertEquals(AspectRatio.LANDSCAPE, CoverLayout.MINIMAL.imageAspectRatio());
    }

    @Test
    void everyVisualLayoutHasARegionRatioAndTypographicHasNone() {
        for (CoverLayout layout : CoverLayout.values()) {
            if (layout.requiresVisual()) {
                assertNotNull(layout.imageAspectRatio(),
                        layout + " needs a visual, so it must define a region ratio");
            }
        }
        assertNull(CoverLayout.TYPOGRAPHIC.imageAspectRatio());
        assertFalse(CoverLayout.TYPOGRAPHIC.requiresVisual());
    }

    @Test
    void fromStringIsLenient() {
        assertEquals(CoverLayout.IMAGE_LED, CoverLayout.fromString("image-led"));
        assertEquals(CoverLayout.EDITORIAL, CoverLayout.fromString("nonsense"));
        assertTrue(CoverLayout.fromString(null).requiresVisual());
    }
}
