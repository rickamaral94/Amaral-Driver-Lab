package com.amaral.driverlab.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app is allowed to say about a device it has not measured.
 *
 * These pin a screen the user actually saw: "this device resolves differences of about 2.0%",
 * rendered directly underneath "the calibration did not pass". The run had settled after one
 * comparison on a 21.8% floor; one comparison leaves cross-validation nothing to hold out, so
 * the read fell through to the assumed default and described it as measured. The one number a
 * user takes from that screen was invented, flattering, and on a failure.
 */
class CalibrationHonestyTest {

    private val config = AbConfig(bootstrapIterations = 400)

    private fun pair(a: List<Double>, b: List<Double>) = a.toDoubleArray() to b.toDoubleArray()

    @Test
    fun `a floor nothing backs says so instead of claiming a measurement`() {
        val assumed = NoiseFloorCalibration.assumed(config)

        assertTrue(assumed.isAssumed)
        assertFalse(
            "an unmeasured floor must not describe itself as measured: ${assumed.describe()}",
            assumed.describe().contains("Measured on"),
        )
        assertTrue(assumed.describe(), assumed.describe().contains("unknown"))
        assertTrue(assumed.describe(), assumed.describe().contains("default"))
    }

    @Test
    fun `a measured floor still reports itself as measured`() {
        val arms = List(3) {
            pair(List(15) { 7_000_000.0 + it * 1000 }, List(15) { 7_000_000.0 + it * 1000 })
        }
        val measured = NullTest.calibrate(arms, config)

        assertFalse(measured.isAssumed)
        assertTrue(measured.describe(), measured.describe().contains("Measured on 3"))
    }

    @Test
    fun `a run too short to cross-validate does not inherit a flattering number`() {
        // Exactly the shape that produced the screenshot: the run settled after one comparison.
        val single = listOf(pair(List(5) { 7_930_000.0 }, List(5) { 8_250_000.0 }))
        val result = NullTest.crossValidated("a".repeat(64), single, config = config)

        assertFalse("a run that could not calibrate must never read as passed", result.passed)
        assertTrue(
            "the verdict should not present the default as this device's resolution: " +
                result.calibration.describe(),
            result.calibration.isAssumed,
        )
    }

    /**
     * A coarse floor is a symptom; an ordering effect is a cause. Reporting only the floor told
     * the user to cool the device down when the finding was drift landing on one arm.
     */
    @Test
    fun `a failure names the ordering effect alongside the coarse floor`() {
        // Arms far enough apart to blow the floor, leaning the same way every comparison.
        val arms = List(NullTest.REQUIRED_CONSECUTIVE_PASSES) { comparison ->
            pair(
                List(15) { 7_000_000.0 + it * 20_000 + comparison * 1000 },
                List(15) { 9_000_000.0 + it * 20_000 + comparison * 1000 },
            )
        }
        val result = NullTest.crossValidated("b".repeat(64), arms, config = config)
        val explained = result.explain()

        assertFalse(result.passed)
        assertTrue("expected the floor to be past the limit: $explained", result.floorTooCoarse)
        if (result.hasOrderingBias) {
            assertTrue(
                "a run with both problems must report the cause, not just the symptom: $explained",
                explained.contains("ordering effect"),
            )
        }
    }
}
