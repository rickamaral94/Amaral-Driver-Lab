package com.amaral.driverlab.report

import com.amaral.driverlab.bench.Arm
import com.amaral.driverlab.bench.BenchmarkOutcome
import com.amaral.driverlab.driver.DriverSource
import com.amaral.driverlab.stats.AbComparison
import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.AbResult
import com.amaral.driverlab.stats.FrametimeSummary
import com.amaral.driverlab.stats.NullTestResult
import com.amaral.driverlab.stats.RankingGate
import com.amaral.driverlab.stats.Verdict
import com.amaral.driverlab.telemetry.ComparabilityWarning
import com.amaral.driverlab.telemetry.DeviceSnapshot
import com.amaral.driverlab.telemetry.PreflightReport
import com.amaral.driverlab.vk.WorkloadSpec
import kotlin.math.abs

/**
 * Turns a finished run into the published record.
 *
 * Every statistic here is derived, never carried over from the UI, so a report and
 * the screen that produced it cannot drift apart. The one thing the builder will
 * not do is invent a comparison: if the null test has not passed, comparisons are
 * still computed and still published, but stamped untrustworthy, and the plain
 * verdict says so instead of naming a winner.
 */
public class ReportBuilder(
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    public fun build(
        outcome: BenchmarkOutcome,
        device: DeviceSnapshot,
        preflight: PreflightReport,
        session: SessionInfo,
        nullTestResult: NullTestResult? = null,
        /**
         * The resolution the null test demonstrated **for each workload**, keyed by workload
         * id. A device is not equally able to resolve every workload — a cheap one can sit
         * inside the frequency noise while an expensive one clears it — so one global floor
         * would either over-claim on the noisy workload or under-claim on the clean one.
         * A workload absent from this map falls back to the gate's floor.
         */
        noiseFloors: Map<String, Double> = emptyMap(),
        warnings: List<ComparabilityWarning> = emptyList(),
        config: AbConfig = AbConfig(),
    ): BenchmarkReport {
        val gate = RankingGate(nullTestResult)
        val comparisonConfig = gate.configFor(config)

        val drivers = listOf(Arm.A, Arm.B).mapNotNull { arm ->
            val record = outcome.records.firstOrNull { it.slot.arm == arm } ?: return@mapNotNull null
            val definition = if (arm == Arm.A) outcome.plan.a else outcome.plan.b
            DriverEntry(
                arm = arm.name,
                label = definition.label,
                unverified = record.unverifiedDriver,
                libraryChecksum = record.identity.libraryChecksum,
                systemDriver = record.identity.source == DriverSource.SYSTEM,
                identity = record.identity,
            )
        }

        val executions = outcome.records.map { record ->
            ExecutionEntry(
                slot = record.slot.slot,
                arm = record.slot.arm.name,
                runIndexWithinArm = record.slot.runIndexWithinArm,
                workload = record.workload.toEntry(),
                frametimesNs = record.frametimesNs.toList(),
                cpuFrametimesNs = record.cpuFrametimesNs.toList(),
                usedGpuTimestamps = record.usedGpuTimestamps,
                imageSha256 = record.imageSha256,
                summary = record.summary.toEntry(),
                telemetryBefore = record.telemetryBefore,
                telemetryAfter = record.telemetryAfter,
            )
        }

        val workloadIds = outcome.plan.workloads.map { it.workloadId }.distinct()
        val comparisons = workloadIds.mapNotNull { workloadId ->
            val a = outcome.runMediansFor(Arm.A, workloadId)
            val b = outcome.runMediansFor(Arm.B, workloadId)
            if (a.isEmpty() || b.isEmpty()) return@mapNotNull null

            // An execution that barely ran cannot tell two drivers apart, so a tie from it is
            // a statement about the protocol rather than about the drivers.
            val tooBrief = outcome.records
                .filter { it.workload.workloadId == workloadId }
                .any { it.summary.totalDurationNs < WorkloadSpec.MINIMUM_USEFUL_GPU_NANOS }

            val workloadConfig = noiseFloors[workloadId]?.let { floor ->
                // Never *narrower* than the gate's claim: a per-workload measurement may
                // widen what the harness admits it can see, never sharpen it.
                comparisonConfig.copy(
                    minimumPracticalDifference =
                        maxOf(comparisonConfig.minimumPracticalDifference, floor),
                )
            } ?: comparisonConfig

            val result = gate.stamp(
                AbComparison.compare(
                    labelA = outcome.plan.a.label,
                    labelB = outcome.plan.b.label,
                    a = a,
                    b = b,
                    lowerIsBetter = true,
                    config = workloadConfig,
                ),
            )
            result.toEntry(
                workloadId,
                warnings + gateWarnings(gate) +
                    if (tooBrief) listOf(ComparabilityWarning.WORKLOAD_TOO_BRIEF) else emptyList(),
            )
        }

        return BenchmarkReport(
            appVersion = appVersion,
            generatedAtEpochMs = clock(),
            session = session,
            device = device,
            preflight = PreflightInfo(
                issues = preflight.issues,
                overridden = preflight.overridden,
                lowConfidence = preflight.lowConfidence,
            ),
            drivers = drivers,
            executions = executions,
            failures = outcome.failures.map {
                FailureEntry(
                    slot = it.slot.slot,
                    arm = it.slot.arm.name,
                    label = it.label,
                    workloadId = it.workload.workloadId,
                    stage = it.stage,
                    message = it.message,
                )
            },
            nullTest = nullTestResult?.toEntry(),
            comparisons = comparisons,
            plainVerdict = PlainVerdict.of(comparisons, outcome, nullTestResult),
        )
    }

    private fun gateWarnings(gate: RankingGate): List<ComparabilityWarning> =
        if (gate.allowed) emptyList() else listOf(ComparabilityWarning.NULL_TEST_NOT_PASSED)
}

internal fun WorkloadSpec.toEntry(): WorkloadEntry = WorkloadEntry(
    workloadId = workloadId,
    width = width,
    height = height,
    frameCount = frameCount,
    warmupFrames = warmupFrames,
    drawsPerFrame = drawsPerFrame,
    trianglesPerDraw = trianglesPerDraw,
)

internal fun FrametimeSummary.toEntry(): SummaryEntry = SummaryEntry(
    frameCount = frameCount,
    medianNs = medianNs,
    meanNs = meanNs,
    stdDevNs = stdDevNs,
    p95Ns = p95Ns,
    p99Ns = p99Ns,
    p999Ns = p999Ns,
    jitterNs = jitterNs,
    stutterCount = stutterCount,
    stuttersPerMinute = stuttersPerMinute,
    openingMedianNs = openingMedianNs,
    sustainedMedianNs = sustainedMedianNs,
    thermalDegradation = thermalDegradation,
    totalDurationNs = totalDurationNs,
)

internal fun AbResult.toEntry(
    workloadId: String,
    warnings: List<ComparabilityWarning>,
): ComparisonEntry = ComparisonEntry(
    workloadId = workloadId,
    labelA = labelA,
    labelB = labelB,
    verdict = verdict.name,
    reason = reason.name,
    runsA = runsA,
    runsB = runsB,
    medianANs = medianA,
    medianBNs = medianB,
    speedupOfA = speedupOfA,
    percentDifference = percentDifference,
    speedupInterval = IntervalEntry(
        point = speedupInterval.point,
        low = speedupInterval.low,
        high = speedupInterval.high,
        confidence = speedupInterval.confidence,
    ),
    pValue = mannWhitney.pValue,
    exactTest = mannWhitney.exact,
    cliffsDelta = cliffsDelta.delta,
    effectMagnitude = cliffsDelta.magnitude.name,
    noiseFloor = noiseFloor,
    trustworthy = trustworthy,
    warnings = warnings.distinct(),
)

internal fun NullTestResult.toEntry(): NullTestEntry = NullTestEntry(
    passed = passed,
    driverSha256 = driverSha256,
    requiredConsecutivePasses = requiredConsecutivePasses,
    completedPasses = completedPasses,
    appliedNoiseFloor = appliedNoiseFloor,
    observedNoiseFloor = observedNoiseFloor,
    firstArmWins = firstArmWins,
    orderingBiasP = orderingBiasP,
    floorTooCoarse = floorTooCoarse,
    hasOrderingBias = hasOrderingBias,
    explanation = explain(),
)

/**
 * The sentence the user reads first (section 11).
 *
 * Written from the same numbers the rest of the report carries, so it cannot say
 * something the table below it contradicts.
 */
public object PlainVerdict {

    public fun of(
        comparisons: List<ComparisonEntry>,
        outcome: BenchmarkOutcome,
        nullTestResult: NullTestResult?,
    ): String {
        if (comparisons.isEmpty()) {
            return "No comparison could be made: " +
                (outcome.failures.firstOrNull()?.message ?: "no workload produced usable frames.")
        }
        if (nullTestResult?.passed != true) {
            val why = nullTestResult?.explain() ?: "This device has not run a null test yet."
            return "Results are shown but not ranked. $why"
        }

        val headline = comparisons.maxByOrNull { abs(it.percentDifference) } ?: comparisons.first()
        val floor = "%.0f".format(headline.noiseFloor * 100)
        val compatibility = "Compatibility: ${outcome.records.size} of " +
            "${outcome.records.size + outcome.failures.size} executions completed."

        return when (Verdict.valueOf(headline.verdict)) {
            Verdict.A_FASTER -> "${headline.labelA} is ${"%.1f".format(abs(headline.percentDifference))}% " +
                "faster than ${headline.labelB} on this device, and the difference is real. $compatibility"

            Verdict.B_FASTER -> "${headline.labelB} is ${"%.1f".format(abs(headline.percentDifference))}% " +
                "faster than ${headline.labelA} on this device, and the difference is real. $compatibility"

            Verdict.TECHNICAL_TIE ->
                if (headline.warnings.contains(ComparabilityWarning.WORKLOAD_TOO_BRIEF)) {
                    // Saying "the same speed" here would be the most misleading thing the app
                    // could do: it did not look long enough to know.
                    "This run was too short to tell ${headline.labelA} and ${headline.labelB} " +
                        "apart. Each workload needs at least " +
                        "${WorkloadSpec.MINIMUM_USEFUL_GPU_NANOS / 1_000_000_000} seconds of GPU " +
                        "time to separate two drivers, and this one had less. Run the Complete " +
                        "profile. $compatibility"
                } else {
                    "${headline.labelA} and ${headline.labelB} are the same speed on this device, " +
                        "within the $floor% this device can resolve. $compatibility"
                }

            Verdict.INCONCLUSIVE -> "This run cannot tell ${headline.labelA} and ${headline.labelB} " +
                "apart — the measurements were too noisy to conclude anything. Let the device cool " +
                "and run it again. $compatibility"
        }
    }
}
