package com.amaral.driverlab.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The truth table section 9 asks for, written out rather than derived, so that a
 * change to the transition map has to be a deliberate change to this list too.
 */
class RunnerStateMachineTest {

    private val allEvents: List<RunnerEvent> = listOf(
        RunnerEvent.Start,
        RunnerEvent.PreflightCleared(overridden = false),
        RunnerEvent.PreflightRefused,
        RunnerEvent.DriverAccepted,
        RunnerEvent.DriverRejected("test"),
        RunnerEvent.WarmupComplete,
        RunnerEvent.ArmComplete,
        RunnerEvent.NextArm,
        RunnerEvent.PlanComplete,
        RunnerEvent.Failed("test", "test"),
        RunnerEvent.Abort,
    )

    /** from state, event name, expected state — every legal move, and nothing else. */
    private val truthTable: List<Triple<RunnerState, String, RunnerState>> = listOf(
        Triple(RunnerState.IDLE, "Start", RunnerState.PREFLIGHT),
        Triple(RunnerState.IDLE, "Failed", RunnerState.FAILED),
        Triple(RunnerState.IDLE, "Abort", RunnerState.ABORTED),

        Triple(RunnerState.PREFLIGHT, "PreflightCleared", RunnerState.LOADING),
        Triple(RunnerState.PREFLIGHT, "PreflightRefused", RunnerState.IDLE),
        Triple(RunnerState.PREFLIGHT, "Failed", RunnerState.FAILED),
        Triple(RunnerState.PREFLIGHT, "Abort", RunnerState.ABORTED),

        Triple(RunnerState.LOADING, "DriverAccepted", RunnerState.WARMUP),
        Triple(RunnerState.LOADING, "DriverRejected", RunnerState.FAILED),
        Triple(RunnerState.LOADING, "Failed", RunnerState.FAILED),
        Triple(RunnerState.LOADING, "Abort", RunnerState.ABORTED),

        Triple(RunnerState.WARMUP, "WarmupComplete", RunnerState.RUNNING),
        Triple(RunnerState.WARMUP, "Failed", RunnerState.FAILED),
        Triple(RunnerState.WARMUP, "Abort", RunnerState.ABORTED),

        Triple(RunnerState.RUNNING, "ArmComplete", RunnerState.COOLDOWN),
        Triple(RunnerState.RUNNING, "Failed", RunnerState.FAILED),
        Triple(RunnerState.RUNNING, "Abort", RunnerState.ABORTED),

        Triple(RunnerState.COOLDOWN, "NextArm", RunnerState.LOADING),
        Triple(RunnerState.COOLDOWN, "PlanComplete", RunnerState.DONE),
        Triple(RunnerState.COOLDOWN, "Failed", RunnerState.FAILED),
        Triple(RunnerState.COOLDOWN, "Abort", RunnerState.ABORTED),
    )

    private fun eventNamed(name: String): RunnerEvent =
        allEvents.first { it::class.simpleName == name }

    @Test
    fun `every legal transition lands where the table says`() {
        for ((from, eventName, expected) in truthTable) {
            val machine = RunnerStateMachine(from)
            val result = machine.apply(eventNamed(eventName))
            assertTrue("$from + $eventName was rejected", result is TransitionResult.Moved)
            assertEquals("$from + $eventName", expected, machine.state)
        }
    }

    @Test
    fun `no transition exists that the table does not list`() {
        val listed = truthTable.map { it.first to it.second }.toSet()
        for (state in RunnerState.entries) {
            for (event in allEvents) {
                val eventName = event::class.simpleName!!
                val machine = RunnerStateMachine(state)
                val moved = machine.apply(event) is TransitionResult.Moved
                val expected = (state to eventName) in listed
                assertEquals("$state + $eventName", expected, moved)
            }
        }
    }

    @Test
    fun `terminal states refuse everything`() {
        for (state in listOf(RunnerState.DONE, RunnerState.FAILED, RunnerState.ABORTED)) {
            assertTrue(state.terminal)
            for (event in allEvents) {
                val machine = RunnerStateMachine(state)
                val result = machine.apply(event)
                assertTrue("$state accepted $event", result is TransitionResult.Rejected)
                assertEquals(state, machine.state)
            }
        }
    }

    @Test
    fun `a rejected transition says why and leaves the state alone`() {
        val machine = RunnerStateMachine(RunnerState.IDLE)
        val result = machine.apply(RunnerEvent.ArmComplete) as TransitionResult.Rejected
        assertEquals(RunnerState.IDLE, machine.state)
        assertTrue(result.reason.contains("no transition from IDLE"))
    }

    /** A driver that did not load ends the run. It never falls through to a fallback. */
    @Test
    fun `a rejected driver is a failure, not a retry`() {
        val machine = RunnerStateMachine(RunnerState.LOADING)
        machine.apply(RunnerEvent.DriverRejected("Vulkan reports Qualcomm proprietary"))
        assertEquals(RunnerState.FAILED, machine.state)
    }

    @Test
    fun `a full two-arm run walks the whole path`() {
        val machine = RunnerStateMachine()
        val script = listOf(
            RunnerEvent.Start,
            RunnerEvent.PreflightCleared(overridden = false),
            RunnerEvent.DriverAccepted,
            RunnerEvent.WarmupComplete,
            RunnerEvent.ArmComplete,
            RunnerEvent.NextArm,
            RunnerEvent.DriverAccepted,
            RunnerEvent.WarmupComplete,
            RunnerEvent.ArmComplete,
            RunnerEvent.PlanComplete,
        )
        for (event in script) {
            assertTrue(
                "unexpected rejection at ${machine.state} on $event",
                machine.apply(event) is TransitionResult.Moved,
            )
        }
        assertEquals(RunnerState.DONE, machine.state)
        assertEquals(script.size, machine.history.size)
        assertEquals(RunnerState.IDLE, machine.history.first().from)
    }

    @Test
    fun `refusing preflight returns to idle so the user can fix it and retry`() {
        val machine = RunnerStateMachine()
        machine.apply(RunnerEvent.Start)
        machine.apply(RunnerEvent.PreflightRefused)
        assertEquals(RunnerState.IDLE, machine.state)
        assertTrue(machine.apply(RunnerEvent.Start) is TransitionResult.Moved)
    }

    @Test
    fun `abort is reachable from every state a run can be in`() {
        for (state in RunnerState.entries.filterNot { it.terminal }) {
            val machine = RunnerStateMachine(state)
            machine.apply(RunnerEvent.Abort)
            assertEquals(RunnerState.ABORTED, machine.state)
        }
    }

    @Test
    fun `the legal event set matches the table for every state`() {
        for (state in RunnerState.entries) {
            val expected = truthTable.filter { it.first == state }.map { it.second }.toSet()
            assertEquals("legal events from $state", expected, RunnerTransitions.legalEvents(state))
        }
    }
}
