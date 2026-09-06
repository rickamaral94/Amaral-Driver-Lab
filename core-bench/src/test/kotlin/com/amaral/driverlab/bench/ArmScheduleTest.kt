package com.amaral.driverlab.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the protocol finding recorded in docs/STATISTICS.md: plain A,B,A,B
 * alternation leaves the first arm in the cooler slot of every pair, which a
 * drifting device turns into a fake winner.
 */
class ArmScheduleTest {

    @Test
    fun `a schedule runs both arms the same number of times`() {
        val slots = ArmSchedule.counterbalanced(runsPerArm = 5)
        assertEquals(10, slots.size)
        assertEquals(5, slots.count { it.arm == Arm.A })
        assertEquals(5, slots.count { it.arm == Arm.B })
        assertEquals((0 until 10).toList(), slots.map { it.slot })
    }

    @Test
    fun `pairs alternate which arm leads`() {
        val slots = ArmSchedule.counterbalanced(runsPerArm = 4)
        assertEquals(
            listOf(Arm.A, Arm.B, Arm.B, Arm.A, Arm.A, Arm.B, Arm.B, Arm.A),
            slots.map { it.arm },
        )
    }

    @Test
    fun `an even number of runs balances exactly within one comparison`() {
        val positions = ArmSchedule.meanSlotPositions(ArmSchedule.counterbalanced(runsPerArm = 4))
        assertEquals(positions.getValue(Arm.A), positions.getValue(Arm.B), 1e-12)
    }

    /**
     * With an odd number of pairs one arm still leads once more than the other. The
     * residual is real and small, and it is why the leading arm flips between
     * comparisons rather than being left alone.
     */
    @Test
    fun `an odd number of runs leaves a residual within one comparison`() {
        val positions = ArmSchedule.meanSlotPositions(ArmSchedule.counterbalanced(runsPerArm = 5))
        assertNotEquals(positions.getValue(Arm.A), positions.getValue(Arm.B))
        assertEquals(0.2, positions.getValue(Arm.B) - positions.getValue(Arm.A), 1e-12)
    }

    @Test
    fun `flipping the lead between comparisons cancels the residual across the set`() {
        // Ten comparisons is what the null test runs, and the imbalance over them
        // has to be zero or a linear drift would favour one arm every time.
        assertEquals(0.0, ArmSchedule.setImbalance(runsPerArm = 5, comparisons = 10), 1e-12)
        assertEquals(0.0, ArmSchedule.setImbalance(runsPerArm = 7, comparisons = 4), 1e-12)
    }

    @Test
    fun `an odd number of comparisons does not fully cancel, and the figure says so`() {
        // Not a bug to hide: with an odd count one comparison has no partner, so the
        // caller can see the residual rather than being told it is zero.
        assertNotEquals(0.0, ArmSchedule.setImbalance(runsPerArm = 5, comparisons = 3))
    }

    @Test
    fun `successive comparisons start with different arms`() {
        assertEquals(Arm.A, ArmSchedule.counterbalanced(5, comparisonIndex = 0).first().arm)
        assertEquals(Arm.B, ArmSchedule.counterbalanced(5, comparisonIndex = 1).first().arm)
    }

    @Test
    fun `run indices within an arm are sequential`() {
        val slots = ArmSchedule.counterbalanced(runsPerArm = 5)
        for (arm in Arm.entries) {
            assertEquals(
                (0 until 5).toList(),
                slots.filter { it.arm == arm }.map { it.runIndexWithinArm },
            )
        }
    }

    @Test
    fun `zero runs is rejected rather than producing an empty protocol`() {
        assertTrue(
            runCatching { ArmSchedule.counterbalanced(0) }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
