package com.amaral.driverlab.bench

import kotlinx.serialization.Serializable

/** Which side of a comparison a run belongs to. */
@Serializable
public enum class Arm { A, B }

/** One scheduled execution: an arm, and where it sits in the running order. */
@Serializable
public data class ArmSlot(val slot: Int, val arm: Arm, val runIndexWithinArm: Int)

/**
 * The order the arms actually run in.
 *
 * The spec asks for interleaving (A,B,A,B) to dilute thermal drift. Simulating it
 * showed that is not enough: the first arm takes the earlier, cooler slot of every
 * pair, so under drift it wins every comparison by a hair — a margin small enough
 * for a calibrated noise floor to swallow and systematic enough to bias a ranking.
 * See docs/STATISTICS.md, finding 3.
 *
 * So pairs are counterbalanced: AB, BA, AB, BA. With an odd number of runs per arm
 * the balance cannot be exact within one comparison — one arm still leads once more
 * than the other — so the leading arm also flips between successive comparisons and
 * the remainder cancels across the set.
 */
public object ArmSchedule {

    /**
     * @param runsPerArm independent runs each driver gets. The protocol asks for at
     *   least five; fewer is allowed here and refused later by the statistics.
     * @param comparisonIndex which comparison in the set this is. Successive values
     *   flip the leading arm, which is what removes the residual bias when
     *   [runsPerArm] is odd.
     */
    public fun counterbalanced(runsPerArm: Int, comparisonIndex: Int = 0): List<ArmSlot> {
        require(runsPerArm > 0) { "runsPerArm must be positive" }
        val slots = mutableListOf<ArmSlot>()
        for (pair in 0 until runsPerArm) {
            val aLeads = (pair + comparisonIndex) % 2 == 0
            val first = if (aLeads) Arm.A else Arm.B
            val second = if (aLeads) Arm.B else Arm.A
            slots += ArmSlot(slot = pair * 2, arm = first, runIndexWithinArm = pair)
            slots += ArmSlot(slot = pair * 2 + 1, arm = second, runIndexWithinArm = pair)
        }
        return slots
    }

    /**
     * Mean slot position of each arm. Equal means the schedule is balanced against
     * a linear drift; the gap is what a drifting device turns into a fake result.
     */
    public fun meanSlotPositions(slots: List<ArmSlot>): Map<Arm, Double> =
        slots.groupBy { it.arm }.mapValues { (_, group) -> group.map { it.slot }.average() }

    /**
     * Total imbalance across a whole set of comparisons. Zero means no linear drift
     * can favour either arm; anything else is the bias the sign test would find.
     */
    public fun setImbalance(runsPerArm: Int, comparisons: Int): Double {
        var total = 0.0
        for (index in 0 until comparisons) {
            val positions = meanSlotPositions(counterbalanced(runsPerArm, index))
            total += (positions.getValue(Arm.A) - positions.getValue(Arm.B))
        }
        return total
    }
}
