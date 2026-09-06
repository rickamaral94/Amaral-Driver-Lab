package com.amaral.driverlab.stats

/**
 * SplitMix64. Deterministic and seedable so that a bootstrap interval is reproducible
 * from the raw series alone: two devices given the same samples and the same seed must
 * derive the same confidence interval, otherwise a published run cannot be re-checked.
 */
public class SplitMix64(seed: Long) {
    private var state: Long = seed

    public fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in `[0, bound)`, rejection-sampled so the distribution stays flat. */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val boundLong = bound.toLong()
        while (true) {
            val bits = nextLong() ushr 1
            val value = bits % boundLong
            if (bits - value + (boundLong - 1) >= 0) return value.toInt()
        }
    }
}
