package com.amaral.driverlab.stats

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Two-sided Mann-Whitney U test.
 *
 * The unit of comparison is one *run*, never one frame. Feeding thousands of frametimes from a
 * single run into this test is pseudo-replication: it treats correlated frames as independent
 * evidence and will report significance for any two runs whatsoever. Summarise each run to a
 * single number first, then compare the handful of runs.
 */
public data class MannWhitneyResult(
    /** U for the first sample. */
    val u: Double,
    val n1: Int,
    val n2: Int,
    val pValue: Double,
    /** True when the exact null distribution was enumerated instead of approximated. */
    val exact: Boolean,
    /** z score of the normal approximation; null when [exact]. */
    val z: Double?,
)

public object MannWhitneyU {

    /** Above this size the exact enumeration is replaced by the tie-corrected normal approximation. */
    private const val EXACT_LIMIT = 20

    public fun test(a: DoubleArray, b: DoubleArray): MannWhitneyResult {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both samples must be non-empty" }
        val n1 = a.size
        val n2 = b.size

        val ranks = midranks(a + b)
        var rankSumA = 0.0
        for (i in 0 until n1) rankSumA += ranks.ranks[i]
        val u = rankSumA - n1 * (n1 + 1) / 2.0

        val hasTies = ranks.tieCorrection > 0.0
        return if (!hasTies && n1 <= EXACT_LIMIT && n2 <= EXACT_LIMIT) {
            MannWhitneyResult(u = u, n1 = n1, n2 = n2, pValue = exactP(n1, n2, u), exact = true, z = null)
        } else {
            val mean = n1.toDouble() * n2 / 2.0
            val n = (n1 + n2).toDouble()
            val variance = (n1.toDouble() * n2 / 12.0) *
                ((n + 1.0) - ranks.tieCorrection / (n * (n - 1.0)))
            if (variance <= 0.0) {
                // Every observation is identical, so there is nothing to separate.
                return MannWhitneyResult(u, n1, n2, pValue = 1.0, exact = false, z = 0.0)
            }
            // Continuity correction pulls the observed U half a step towards the mean.
            val deviation = kotlin.math.abs(u - mean)
            val z = if (deviation <= 0.5) 0.0 else (deviation - 0.5) / sqrt(variance)
            MannWhitneyResult(u, n1, n2, pValue = Gaussian.twoSidedP(z), exact = false, z = z)
        }
    }

    private class Ranking(val ranks: DoubleArray, val tieCorrection: Double)

    /** Average ranks within tied groups, plus Σ(t³ − t) for the variance correction. */
    private fun midranks(values: DoubleArray): Ranking {
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(values.size)
        var tieCorrection = 0.0
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val groupSize = j - i + 1
            // Ranks are 1-based; the shared rank is the mean of the positions the group occupies.
            val sharedRank = (i + j + 2) / 2.0
            for (k in i..j) ranks[order[k]] = sharedRank
            if (groupSize > 1) {
                val t = groupSize.toDouble()
                tieCorrection += t * t * t - t
            }
            i = j + 1
        }
        return Ranking(ranks, tieCorrection)
    }

    /**
     * Exact two-sided p by enumerating the null distribution of U.
     *
     * counts[m][n][k] is the number of ways m and n observations can interleave to produce U = k,
     * built from the standard recurrence N(m,n,k) = N(m−1,n,k−n) + N(m,n−1,k).
     */
    private fun exactP(n1: Int, n2: Int, u: Double): Double {
        val maxU = n1 * n2
        val counts = Array(n1 + 1) { Array(n2 + 1) { DoubleArray(maxU + 1) } }
        for (m in 0..n1) {
            for (n in 0..n2) {
                if (m == 0 || n == 0) {
                    counts[m][n][0] = 1.0
                    continue
                }
                for (k in 0..maxU) {
                    var total = counts[m][n - 1][k]
                    if (k - n >= 0) total += counts[m - 1][n][k - n]
                    counts[m][n][k] = total
                }
            }
        }
        val distribution = counts[n1][n2]
        val totalWays = distribution.sum()
        // U is an integer whenever there are no ties, but it arrives as a Double.
        val observed = Math.round(u).toInt().coerceIn(0, maxU)

        var atOrBelow = 0.0
        for (k in 0..observed) atOrBelow += distribution[k]
        var atOrAbove = 0.0
        for (k in observed..maxU) atOrAbove += distribution[k]

        val oneSided = min(atOrBelow, atOrAbove) / totalWays
        return min(1.0, 2.0 * oneSided)
    }
}
