package com.amaral.driverlab.stats

import kotlin.math.abs

/**
 * Cliff's delta: the probability that a value drawn from the first sample exceeds one drawn
 * from the second, minus the probability of the reverse. Ranges over [-1, 1].
 *
 * This is the guard against the failure mode Mann-Whitney has on its own — with enough runs,
 * a difference of no practical size still reaches significance. A driver that is 0.2% slower
 * is not "slower", it is the same driver.
 */
public enum class EffectMagnitude { NEGLIGIBLE, SMALL, MEDIUM, LARGE }

public data class CliffsDeltaResult(
    val delta: Double,
    val magnitude: EffectMagnitude,
) {
    public val negligible: Boolean get() = magnitude == EffectMagnitude.NEGLIGIBLE
}

public object CliffsDelta {

    /** Romano et al. thresholds, the ones usually quoted alongside Cliff's delta. */
    public const val NEGLIGIBLE_BELOW: Double = 0.147
    public const val SMALL_BELOW: Double = 0.330
    public const val MEDIUM_BELOW: Double = 0.474

    public fun of(a: DoubleArray, b: DoubleArray): CliffsDeltaResult {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both samples must be non-empty" }
        var greater = 0L
        var less = 0L
        for (x in a) {
            for (y in b) {
                if (x > y) greater++ else if (x < y) less++
            }
        }
        val delta = (greater - less).toDouble() / (a.size.toLong() * b.size)
        return CliffsDeltaResult(delta, magnitudeOf(delta))
    }

    public fun magnitudeOf(delta: Double): EffectMagnitude {
        val d = abs(delta)
        return when {
            d < NEGLIGIBLE_BELOW -> EffectMagnitude.NEGLIGIBLE
            d < SMALL_BELOW -> EffectMagnitude.SMALL
            d < MEDIUM_BELOW -> EffectMagnitude.MEDIUM
            else -> EffectMagnitude.LARGE
        }
    }
}
