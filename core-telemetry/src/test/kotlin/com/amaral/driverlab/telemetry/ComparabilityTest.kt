package com.amaral.driverlab.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparabilityTest {

    private fun context(
        session: String = "session-1",
        device: String = "odin2-portal",
        spec: String = "tiling_gmem/v1@1920x1080x300",
        throttled: Boolean = false,
        overridden: Boolean = false,
    ) = RunContext(session, device, spec, throttled, overridden)

    @Test
    fun `two clean runs from one session need no caveat`() {
        assertTrue(Comparability.check(context(), context(), nullTestPassed = true).isEmpty())
    }

    /** P7: results from different thermal sessions carry an explicit warning. */
    @Test
    fun `different thermal sessions warn`() {
        assertEquals(
            listOf(ComparabilityWarning.DIFFERENT_THERMAL_SESSION),
            Comparability.check(context(), context(session = "session-2"), nullTestPassed = true),
        )
    }

    @Test
    fun `a failed null test outranks every other warning`() {
        val warnings = Comparability.check(
            context(),
            context(session = "session-2", throttled = true),
            nullTestPassed = false,
        )
        assertEquals(ComparabilityWarning.NULL_TEST_NOT_PASSED, warnings.first())
    }

    @Test
    fun `differing workload specs are not comparable`() {
        assertTrue(
            Comparability.check(
                context(),
                context(spec = "tiling_gmem/v1@1280x720x300"),
                nullTestPassed = true,
            ).contains(ComparabilityWarning.DIFFERENT_WORKLOAD_SPEC),
        )
    }

    @Test
    fun `throttling on either side warns once`() {
        assertEquals(
            listOf(ComparabilityWarning.THROTTLED_DURING_RUN),
            Comparability.check(context(throttled = true), context(), nullTestPassed = true),
        )
    }

    @Test
    fun `an overridden preflight follows the run into the comparison`() {
        assertTrue(
            Comparability.check(context(), context(overridden = true), nullTestPassed = true)
                .contains(ComparabilityWarning.PREFLIGHT_OVERRIDDEN),
        )
    }

    @Test
    fun `a different device is never quietly compared`() {
        assertTrue(
            Comparability.check(context(), context(device = "pixel-8"), nullTestPassed = true)
                .contains(ComparabilityWarning.DIFFERENT_DEVICE),
        )
    }

    @Test
    fun `a session is only comparable with itself`() {
        val sample = TelemetrySample(0, ThermalStatus.NONE, BatteryState(90, 0, false, false, 30.0), emptyList())
        val first = ThermalSession("s1", 0, sample)
        assertTrue(first.comparableWith(first.copy()))
        assertTrue(!first.comparableWith(ThermalSession("s2", 0, sample)))
    }
}
