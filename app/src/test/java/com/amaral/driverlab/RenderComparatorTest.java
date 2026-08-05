package com.amaral.driverlab;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RenderComparatorTest {
    @Test
    public void identicalImagesMatchExactly() {
        int[] pixels = new int[16];
        Arrays.fill(pixels, 0xff123456);

        RenderComparator.Result result = RenderComparator.compare(
                pixels, pixels.clone(), 4, 4, 0, 2, 100.0);

        assertEquals(100.0, result.pixelMatchPercent(), 0.0);
        assertEquals(0, result.divergentBlocks.size());
        assertTrue(result.passes(0));
    }

    @Test
    public void perChannelRoundingInsideToleranceIsAccepted() {
        int[] reference = {0xff102030};
        int[] candidate = {0xff12222f};

        RenderComparator.Result result = RenderComparator.compare(
                reference, candidate, 1, 1, 2, 1, 100.0);

        assertEquals(100.0, result.pixelMatchPercent(), 0.0);
        assertEquals(2, result.maxChannelDelta);
        assertTrue(result.passes(0));
    }

    @Test
    public void onePixelDoesNotMakeAHealthyBlockStructural() {
        int[] reference = new int[16 * 16];
        int[] candidate = reference.clone();
        candidate[0] = 0xffffffff;

        RenderComparator.Result result = RenderComparator.compare(
                reference, candidate, 16, 16, 0, 16, 99.0);

        assertEquals(99.609375, result.pixelMatchPercent(), 0.000001);
        assertEquals(0, result.divergentBlocks.size());
    }

    @Test
    public void structuralDifferenceProducesDivergentBlocksAndFails() {
        int[] reference = new int[16 * 16];
        int[] candidate = new int[16 * 16];
        Arrays.fill(reference, 0xff000000);
        Arrays.fill(candidate, 0xffffffff);

        RenderComparator.Result result = RenderComparator.compare(
                reference, candidate, 16, 16, 0, 8, 99.0);

        assertEquals(0.0, result.pixelMatchPercent(), 0.0);
        assertEquals(4, result.divergentBlocks.size());
        assertFalse(result.passes(0));
        assertTrue(result.passes(4));
    }
}
