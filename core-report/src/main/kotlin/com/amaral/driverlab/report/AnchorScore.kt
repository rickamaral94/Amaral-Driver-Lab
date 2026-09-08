package com.amaral.driverlab.report

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A driver every submission is measured against, pinned by content hash.
 *
 * The anchor is what makes a score mean the same thing on two different phones. A raw
 * score cannot: the same driver on an Adreno 740 scores what the phone's silicon bin,
 * cooling and governor allow, so a leaderboard of raw scores ranks the hardware and the
 * room temperature. Dividing by an anchor measured *in the same thermal session* cancels
 * the device out, and what survives is the driver.
 *
 * That only works if the anchor is literally the same code everywhere, which is why it is
 * identified by the SHA-256 of the `.so` and not by a name or a version string. "The system
 * driver" is not an anchor: it is a different binary on every device, so a ratio against it
 * is not comparable across devices either.
 */
@Serializable
public data class Anchor(
    /** Stable id used in the leaderboard, e.g. `turnip-25.1.0`. */
    val id: String,
    val libraryChecksum: String,
    val displayName: String,
) {
    init {
        require(libraryChecksum.length == 64) {
            "an anchor is pinned by the SHA-256 of its library, got '${libraryChecksum}'"
        }
    }
}

/**
 * What a candidate driver scored against an anchor on one workload.
 *
 * [PARITY] is the anchor itself. Above it the candidate is faster, below it slower, and the
 * distance is proportional: 1240 is 24% faster than the anchor, 800 is 20% slower.
 */
public data class AnchorScore(
    val workloadId: String,
    val anchorId: String,
    val candidateLabel: String,
    val score: Int,
    val low: Int,
    val high: Int,
    /** True when the interval straddles [PARITY]: this driver and the anchor are not separable. */
    val tiesWithAnchor: Boolean,
    /**
     * False when the comparison behind it may not be ranked — the device's null test has not
     * passed, or the run was flagged. The score is still shown; it just does not place.
     */
    val rankable: Boolean,
) {
    public companion object {
        /** The anchor's own score. A candidate matching it exactly scores this. */
        public const val PARITY: Int = 1000

        /**
         * Derives the score from a published comparison.
         *
         * @param anchorChecksum the SHA-256 the [Anchor] pins.
         * @param checksumA the library checksum of arm A, [checksumB] of arm B. Exactly one
         *   must be the anchor: a comparison of two candidates has no scale, and a comparison
         *   of the anchor with itself has nothing to place.
         * @return null when neither arm is the anchor, when both are, or when the comparison
         *   carries no usable ratio.
         */
        public fun of(
            comparison: ComparisonEntry,
            anchorId: String,
            anchorChecksum: String,
            checksumA: String,
            checksumB: String,
        ): AnchorScore? {
            val anchorIsA = checksumA == anchorChecksum
            val anchorIsB = checksumB == anchorChecksum
            if (anchorIsA == anchorIsB) return null

            val ratio = comparison.speedupOfA
            val low = comparison.speedupInterval.low
            val high = comparison.speedupInterval.high
            if (!ratio.isFinite() || !low.isFinite() || !high.isFinite() || ratio <= 0.0) return null

            // speedupOfA is medianB / medianA, so it is already anchor-over-candidate when the
            // anchor is arm B. When the anchor is arm A the ratio has to be inverted — and
            // inverting an interval swaps its ends, which is the part that is easy to get
            // wrong and would silently mirror every score around parity.
            val (point, lowRatio, highRatio) = if (anchorIsB) {
                Triple(ratio, low, high)
            } else {
                if (low <= 0.0 || high <= 0.0) return null
                Triple(1.0 / ratio, 1.0 / high, 1.0 / low)
            }

            return AnchorScore(
                workloadId = comparison.workloadId,
                anchorId = anchorId,
                candidateLabel = if (anchorIsB) comparison.labelA else comparison.labelB,
                score = points(point),
                low = points(lowRatio),
                high = points(highRatio),
                // Read off the interval rather than the verdict string, so the score and the
                // claim about it come from the same number.
                tiesWithAnchor = lowRatio <= 1.0 && highRatio >= 1.0,
                rankable = comparison.trustworthy,
            )
        }

        private fun points(ratio: Double): Int = (ratio * PARITY).roundToInt()
    }
}
