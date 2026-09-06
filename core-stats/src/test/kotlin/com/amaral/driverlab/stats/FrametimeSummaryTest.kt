package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrametimeSummaryTest {

    @Test
    fun `percentiles are taken on frametime and describe the slow tail`() {
        // 995 frames at 16ms and 5 hitches at 100ms. Half a percent of frames are hitches, so the
        // 99th percentile is still smooth and the 99.9th is entirely inside the hitches.
        val frames = LongArray(1000) { if (it % 200 == 0) 100_000_000L else 16_000_000L }
        val summary = FrametimeSummary.of(frames)
        assertEquals(16_000_000.0, summary.medianNs, 1.0)
        assertEquals(16_000_000.0, summary.p99Ns, 1.0)
        assertEquals(100_000_000.0, summary.p999Ns, 1.0)
        assertEquals(5, summary.stutterCount)
    }

    @Test
    fun `a hitch rarer than the percentile cannot move it`() {
        // One hitch in a thousand frames sits above the 99.9th percentile by definition. Reporting
        // it there would overstate what the percentile means; the stutter count is what catches it.
        val frames = LongArray(1000) { if (it == 500) 100_000_000L else 16_000_000L }
        val summary = FrametimeSummary.of(frames)
        assertTrue(summary.p999Ns < 20_000_000.0)
        assertEquals(1, summary.stutterCount)
    }

    @Test
    fun `fps conversion happens only at the edge`() {
        assertEquals(60.0, FrametimeSummary.nsToFps(16_666_666.7), 0.001)
        assertEquals(0.0, FrametimeSummary.nsToFps(0.0), 0.0)
    }

    @Test
    fun `jitter reads consecutive differences, not spread`() {
        // Same set of values, different order: a smooth ramp and a sawtooth have equal std dev.
        val smooth = LongArray(100) { 16_000_000L + it * 10_000L }
        val sawtooth = LongArray(100) { if (it % 2 == 0) 16_000_000L else 16_990_000L }
        assertTrue(
            "sawtooth jitter ${FrametimeSummary.of(sawtooth).jitterNs} should exceed " +
                "ramp jitter ${FrametimeSummary.of(smooth).jitterNs}",
            FrametimeSummary.of(sawtooth).jitterNs > FrametimeSummary.of(smooth).jitterNs * 10,
        )
    }

    @Test
    fun `stutters are counted against twice the median`() {
        val frames = LongArray(300) { if (it % 100 == 0) 40_000_000L else 16_000_000L }
        val summary = FrametimeSummary.of(frames)
        assertEquals(3, summary.stutterCount)
        assertTrue(summary.stuttersPerMinute > 0.0)
    }

    @Test
    fun `thermal degradation compares the first third with the last`() {
        // Frames get 20% slower across the run.
        val frames = LongArray(300) { (16_000_000.0 * (1.0 + 0.2 * it / 299.0)).toLong() }
        val summary = FrametimeSummary.of(frames)
        assertTrue(
            "expected visible degradation, got ${summary.thermalDegradation}",
            summary.thermalDegradation > 0.10,
        )
        assertTrue(summary.sustainedMedianNs > summary.openingMedianNs)
    }

    @Test
    fun `a steady run reports no degradation`() {
        val frames = LongArray(300) { 16_000_000L }
        assertEquals(0.0, FrametimeSummary.of(frames).thermalDegradation, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a workload that produced no frames is an error, not a zero`() {
        FrametimeSummary.of(LongArray(0))
    }
}
