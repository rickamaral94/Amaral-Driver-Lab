package com.amaral.driverlab;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AmaralColorsTest {
    @Test
    public void primaryTextMeetsAccessibleContrastOnCoreSurfaces() {
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.TEXT_PRIMARY, AmaralColors.BACKGROUND) >= 7.0d);
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.TEXT_PRIMARY, AmaralColors.SURFACE) >= 7.0d);
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.TEXT_SECONDARY, AmaralColors.BACKGROUND) >= 4.5d);
    }

    @Test
    public void functionalColorsRemainDistinguishableFromBackground() {
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.SUCCESS, AmaralColors.BACKGROUND) >= 4.5d);
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.WARNING, AmaralColors.BACKGROUND) >= 4.5d);
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.ERROR, AmaralColors.BACKGROUND) >= 4.5d);
        assertTrue(AmaralColors.contrastRatio(
                AmaralColors.INFO, AmaralColors.BACKGROUND) >= 4.5d);
    }
}
