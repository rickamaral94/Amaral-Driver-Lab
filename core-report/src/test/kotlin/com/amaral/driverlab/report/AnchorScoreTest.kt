package com.amaral.driverlab.report

import com.amaral.driverlab.stats.Verdict
import com.amaral.driverlab.telemetry.ComparabilityWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorScoreTest {

    private val anchorSha = "a".repeat(64)
    private val candidateSha = "c".repeat(64)

    /**
     * @param speedupOfA medianB / medianA, the convention `AbComparison` produces: above 1.0
     *   means arm A is the faster one.
     */
    private fun comparison(
        speedupOfA: Double,
        low: Double,
        high: Double,
        trustworthy: Boolean = true,
    ) = ComparisonEntry(
        workloadId = "baseline/v1",
        labelA = "Candidate",
        labelB = "Anchor",
        verdict = Verdict.A_FASTER.name,
        reason = "SIGNIFICANT",
        runsA = 5,
        runsB = 5,
        medianANs = 5_780_000.0,
        medianBNs = 7_570_000.0,
        speedupOfA = speedupOfA,
        percentDifference = (speedupOfA - 1.0) * 100.0,
        speedupInterval = IntervalEntry(point = speedupOfA, low = low, high = high, confidence = 0.95),
        pValue = 0.008,
        exactTest = true,
        cliffsDelta = -1.0,
        effectMagnitude = "LARGE",
        noiseFloor = 0.02,
        trustworthy = trustworthy,
        warnings = emptyList<ComparabilityWarning>(),
    )

    @Test
    fun `a candidate faster than the anchor scores above parity`() {
        val score = AnchorScore.of(
            comparison(speedupOfA = 1.24, low = 1.18, high = 1.31),
            anchorId = "turnip-25.1.0",
            anchorChecksum = anchorSha,
            checksumA = candidateSha,
            checksumB = anchorSha,
        )!!

        assertEquals(1240, score.score)
        assertEquals(1180, score.low)
        assertEquals(1310, score.high)
        assertFalse(score.tiesWithAnchor)
        assertEquals("Candidate", score.candidateLabel)
    }

    @Test
    fun `the same measurement with the arms swapped gives the same score`() {
        // The only difference is which side of the plan the anchor sat on, which is a
        // scheduling detail. If it moved the score, every leaderboard row would depend on
        // how the user happened to fill in the setup screen.
        val anchorIsB = AnchorScore.of(
            comparison(speedupOfA = 1.25, low = 1.20, high = 1.30),
            "turnip-25.1.0", anchorSha, checksumA = candidateSha, checksumB = anchorSha,
        )!!
        // Arms swapped: A is now the anchor, so the ratio is reported the other way up.
        val anchorIsA = AnchorScore.of(
            comparison(speedupOfA = 1.0 / 1.25, low = 1.0 / 1.30, high = 1.0 / 1.20),
            "turnip-25.1.0", anchorSha, checksumA = anchorSha, checksumB = candidateSha,
        )!!

        assertEquals(anchorIsB.score, anchorIsA.score)
        assertEquals(anchorIsB.low, anchorIsA.low)
        assertEquals(anchorIsB.high, anchorIsA.high)
    }

    @Test
    fun `inverting the ratio swaps the ends of the interval`() {
        val score = AnchorScore.of(
            comparison(speedupOfA = 0.80, low = 0.75, high = 0.85),
            "turnip-25.1.0", anchorSha, checksumA = anchorSha, checksumB = candidateSha,
        )!!

        // 1/0.80 = 1.25 point; the low end comes from 1/0.85 and the high from 1/0.75.
        assertEquals(1250, score.score)
        assertEquals(1176, score.low)
        assertEquals(1333, score.high)
        assertTrue("low must stay below high", score.low < score.high)
    }

    @Test
    fun `a slower candidate scores below parity`() {
        val score = AnchorScore.of(
            comparison(speedupOfA = 0.80, low = 0.76, high = 0.84),
            "turnip-25.1.0", anchorSha, checksumA = candidateSha, checksumB = anchorSha,
        )!!

        assertEquals(800, score.score)
        assertTrue(score.score < AnchorScore.PARITY)
    }

    @Test
    fun `an interval straddling parity is a tie with the anchor`() {
        val score = AnchorScore.of(
            comparison(speedupOfA = 1.01, low = 0.97, high = 1.05),
            "turnip-25.1.0", anchorSha, checksumA = candidateSha, checksumB = anchorSha,
        )!!

        assertTrue(score.tiesWithAnchor)
        assertEquals(1010, score.score)
    }

    @Test
    fun `an untrustworthy comparison still scores but does not place`() {
        val score = AnchorScore.of(
            comparison(speedupOfA = 1.24, low = 1.18, high = 1.31, trustworthy = false),
            "turnip-25.1.0", anchorSha, checksumA = candidateSha, checksumB = anchorSha,
        )!!

        assertEquals(1240, score.score)
        assertFalse(score.rankable)
    }

    @Test
    fun `a comparison with no anchor in it has no scale`() {
        assertNull(
            AnchorScore.of(
                comparison(speedupOfA = 1.24, low = 1.18, high = 1.31),
                "turnip-25.1.0", anchorSha,
                checksumA = candidateSha,
                checksumB = "d".repeat(64),
            ),
        )
    }

    @Test
    fun `the anchor compared with itself has nothing to place`() {
        assertNull(
            AnchorScore.of(
                comparison(speedupOfA = 1.0, low = 0.98, high = 1.02),
                "turnip-25.1.0", anchorSha, checksumA = anchorSha, checksumB = anchorSha,
            ),
        )
    }

    @Test
    fun `a ratio that is not a number produces no score`() {
        assertNull(
            AnchorScore.of(
                comparison(speedupOfA = Double.NaN, low = Double.NaN, high = Double.NaN),
                "turnip-25.1.0", anchorSha, checksumA = candidateSha, checksumB = anchorSha,
            ),
        )
    }

    @Test
    fun `an anchor must be pinned by a full sha256`() {
        val error = runCatching { Anchor("turnip", "abc", "Turnip") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
