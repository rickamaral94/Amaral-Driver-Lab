package com.amaral.driverlab.stats

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wilcoxon signed-rank, two-sided, against a null median of zero.
 *
 * Used on the log ratios of the A/A comparisons to ask whether the protocol favours one arm.
 * A driver compared with itself has no reason to prefer either side, so the log ratios should
 * sit around zero; a consistent lean means the harness is manufacturing the difference.
 *
 * It replaced a plain sign test on the same values when the null test went from twenty
 * comparisons to a cross-validated ten. Halving the pool cost the sign test most of its
 * power — against a deliberately biased protocol it fired 17 times in 40 — because it reads
 * only the direction of each comparison and throws the size away. Signed-rank uses both, and
 * on the same simulations catches the same biased protocol 28 times in 40 while flagging the
 * correct one just as rarely, 1 in 40. Same false-positive rate, more of the effect seen.
 *
 * The cost is an assumption: signed-rank wants the values roughly symmetric about their
 * median, where a sign test wants nothing at all. For log ratios of one driver against
 * itself that is a mild ask — the asymmetry a raw ratio has is exactly what taking the log
 * removes.
 */
public object SignedRankTest {

    /** Above this many non-zero values the exact enumeration gives way to the normal form. */
    public const val EXACT_LIMIT: Int = 20

    /** Fewer than this and no split is surprising enough to mean anything. */
    public const val MINIMUM_USABLE: Int = 5

    /**
     * @param values one per comparison, already centred so that zero is "no preference" —
     *   log of the ratio, not the ratio.
     * @return the two-sided p value, or 1.0 when there is too little to say.
     */
    public fun twoSidedP(values: List<Double>): Double {
        val nonZero = values.filter { it != 0.0 && it.isFinite() }
        val n = nonZero.size
        if (n < MINIMUM_USABLE) return 1.0

        val ranks = averageRanks(nonZero.map { abs(it) })
        val positive = nonZero.indices.sumOf { if (nonZero[it] > 0) ranks[it] else 0.0 }

        return if (n <= EXACT_LIMIT) {
            exactTwoSidedP(ranks, positive)
        } else {
            normalTwoSidedP(n, positive)
        }
    }

    /** Ties share the average of the ranks they span, the standard correction. */
    private fun averageRanks(magnitudes: List<Double>): DoubleArray {
        val order = magnitudes.withIndex().sortedBy { it.value }
        val ranks = DoubleArray(magnitudes.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && order[j + 1].value == order[i].value) j++
            val average = (i + j + 2) / 2.0
            for (k in i..j) ranks[order[k].index] = average
            i = j + 1
        }
        return ranks
    }

    /**
     * Enumerates every assignment of signs over the observed ranks.
     *
     * Exact including when ranks are tied. The null hypothesis is that the values are
     * symmetric about zero, so conditioning on the magnitudes actually seen and enumerating
     * the 2^n sign assignments *is* the null distribution — average ranks and all. An
     * earlier version fell back to the normal approximation whenever anything tied, which
     * was the more cautious-looking choice and the less accurate one: at these sample sizes
     * the approximation is the thing that needs excusing, not the enumeration.
     */
    private fun exactTwoSidedP(ranks: DoubleArray, observed: Double): Double {
        val n = ranks.size
        var atLeastAsExtreme = 0L
        val total = 1L shl n
        val centre = ranks.sum() / 2.0
        val distance = abs(observed - centre)
        for (mask in 0 until total) {
            var sum = 0.0
            for (bit in 0 until n) if ((mask shr bit) and 1L == 1L) sum += ranks[bit]
            if (abs(sum - centre) >= distance - 1e-9) atLeastAsExtreme++
        }
        return (atLeastAsExtreme.toDouble() / total).coerceAtMost(1.0)
    }

    private fun normalTwoSidedP(n: Int, positive: Double): Double {
        val mean = n * (n + 1) / 4.0
        val sd = sqrt(n * (n + 1.0) * (2.0 * n + 1.0) / 24.0)
        if (sd == 0.0) return 1.0
        return Gaussian.twoSidedP((positive - mean) / sd)
    }
}
