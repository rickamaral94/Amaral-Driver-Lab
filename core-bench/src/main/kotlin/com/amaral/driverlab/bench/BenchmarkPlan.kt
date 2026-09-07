package com.amaral.driverlab.bench

import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.vk.WorkloadSpec

/** What one arm of a comparison runs. */
public data class ArmDefinition(
    val arm: Arm,
    val driver: RequestedDriver,
    val label: String,
)

/**
 * A whole benchmark: which drivers, which workloads, how many runs, in what order.
 *
 * Built once before anything starts, so the running order is fixed in advance and
 * cannot drift in response to results as they come in.
 */
public data class BenchmarkPlan(
    val a: ArmDefinition,
    val b: ArmDefinition,
    val workloads: List<WorkloadSpec>,
    val runsPerArm: Int = DEFAULT_RUNS_PER_ARM,
    val comparisonIndex: Int = 0,
    val cooldownBetweenArmsMs: Long = DEFAULT_ARM_COOLDOWN_MS,
    /** True when both arms are the same driver, i.e. this is an A/A run. */
    val isNullTest: Boolean = false,
) {
    init {
        require(workloads.isNotEmpty()) { "a plan must run at least one workload" }
        require(runsPerArm > 0) { "runsPerArm must be positive" }
    }

    public val schedule: List<ArmSlot> = ArmSchedule.counterbalanced(runsPerArm, comparisonIndex)

    /** One entry per driver execution the runner will perform, in order. */
    public val executions: List<PlannedExecution> = schedule.flatMap { slot ->
        val definition = if (slot.arm == Arm.A) a else b
        workloads.map { workload ->
            PlannedExecution(
                slot = slot,
                driver = definition.driver,
                label = definition.label,
                workload = workload,
            )
        }
    }

    public val totalExecutions: Int get() = executions.size

    /** Rough duration, used for the progress bar's ETA. Never used to decide work. */
    public fun estimatedFrames(): Long = executions.sumOf {
        (it.workload.frameCount + it.workload.warmupFrames).toLong()
    }

    public companion object {
        /** Section 7: at least five independent runs per driver. */
        public const val DEFAULT_RUNS_PER_ARM: Int = 5
        public const val DEFAULT_ARM_COOLDOWN_MS: Long = 20_000L

        /** Builds the A/A plan: the same driver on both sides. */
        public fun nullTest(
            driver: RequestedDriver,
            label: String,
            workloads: List<WorkloadSpec>,
            runsPerArm: Int = DEFAULT_RUNS_PER_ARM,
            comparisonIndex: Int = 0,
        ): BenchmarkPlan = BenchmarkPlan(
            a = ArmDefinition(Arm.A, driver, "$label (arm 1)"),
            b = ArmDefinition(Arm.B, driver, "$label (arm 2)"),
            workloads = workloads,
            runsPerArm = runsPerArm,
            comparisonIndex = comparisonIndex,
            isNullTest = true,
        )
    }
}

public data class PlannedExecution(
    val slot: ArmSlot,
    val driver: RequestedDriver,
    val label: String,
    val workload: WorkloadSpec,
)

/** The named profiles the UI offers. Section 11 asks for exactly two. */
public object BenchmarkProfiles {

    /** Roughly three minutes: enough to see a large regression, not enough to rank. */
    public fun quick(): List<WorkloadSpec> = listOf(
        WorkloadSpec.baseline().copy(frameCount = 400, warmupFrames = 60),
        WorkloadSpec.tilingGmem().copy(frameCount = 200, warmupFrames = 40),
    )

    /** The full protocol. Roughly twenty minutes, and the only one that can rank. */
    public fun complete(): List<WorkloadSpec> = listOf(
        WorkloadSpec.baseline(),
        WorkloadSpec.tilingGmem(),
    )
}
