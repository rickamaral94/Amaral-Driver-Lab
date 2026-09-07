package com.amaral.driverlab.report

import com.amaral.driverlab.driver.DriverIdentity
import com.amaral.driverlab.telemetry.ComparabilityWarning
import com.amaral.driverlab.telemetry.DeviceSnapshot
import com.amaral.driverlab.telemetry.PreflightIssue
import com.amaral.driverlab.telemetry.TelemetrySample
import kotlinx.serialization.Serializable

/**
 * The published result format.
 *
 * `schemaVersion` is an integer that goes up whenever a reader written for the old
 * shape would get the wrong answer from the new one — not when a field is added.
 * The ingestion pipeline validates against `schema/result-v<N>.schema.json`, and a
 * payload whose version it does not know is rejected with that as the reason
 * rather than parsed hopefully.
 *
 * Every raw frametime series is included. Aggregates alone cannot be re-checked,
 * and a leaderboard that cannot re-check its entries is a leaderboard of claims.
 */
public object ReportSchema {
    public const val VERSION: Int = 1
}

@Serializable
public data class BenchmarkReport(
    val schemaVersion: Int = ReportSchema.VERSION,
    val appVersion: String,
    val generatedAtEpochMs: Long,
    val session: SessionInfo,
    val device: DeviceSnapshot,
    val preflight: PreflightInfo,
    val drivers: List<DriverEntry>,
    val executions: List<ExecutionEntry>,
    val failures: List<FailureEntry>,
    val nullTest: NullTestEntry?,
    val comparisons: List<ComparisonEntry>,
    /** One sentence a non-specialist can read. Derived, never authored by hand. */
    val plainVerdict: String,
)

@Serializable
public data class SessionInfo(
    val id: String,
    val thermalSessionId: String,
    val startedAtEpochMs: Long,
    val runnerFinalState: String,
    /** Optional nickname the user chose. There is no device identifier anywhere. */
    val submitterAlias: String = "",
)

@Serializable
public data class PreflightInfo(
    val issues: List<PreflightIssue>,
    val overridden: Boolean,
    /** True when the run started with blocking issues overridden. Ingestion refuses these. */
    val lowConfidence: Boolean,
)

@Serializable
public data class DriverEntry(
    val arm: String,
    val label: String,
    /** True when the library's hash is not in the allowlist. Runs, but is not a known build. */
    val unverified: Boolean,
    /** SHA-256 of the `.so` that ran. Empty only for the system driver. */
    val libraryChecksum: String,
    /** True for the system driver, which is a reference and is never ranked. */
    val systemDriver: Boolean,
    val identity: DriverIdentity,
)

@Serializable
public data class WorkloadEntry(
    val workloadId: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val warmupFrames: Int,
    val drawsPerFrame: Int,
    val trianglesPerDraw: Int,
) {
    /**
     * Identifies the exact shape of the work. Two results are only comparable when
     * these match, so it is part of the record rather than recomputed by a reader.
     */
    public fun fingerprint(): String =
        "$workloadId@${width}x$height/f$frameCount/w$warmupFrames/d$drawsPerFrame/t$trianglesPerDraw"
}

@Serializable
public data class ExecutionEntry(
    val slot: Int,
    val arm: String,
    val runIndexWithinArm: Int,
    val workload: WorkloadEntry,
    /** Nanoseconds per frame, in order. The whole series, not a sample of it. */
    val frametimesNs: List<Long>,
    val cpuFrametimesNs: List<Long>,
    /** False when the device had no usable timestamps and host time was used instead. */
    val usedGpuTimestamps: Boolean,
    val imageSha256: String,
    val summary: SummaryEntry,
    val telemetryBefore: TelemetrySample,
    val telemetryAfter: TelemetrySample,
)

@Serializable
public data class SummaryEntry(
    val frameCount: Int,
    val medianNs: Double,
    val meanNs: Double,
    val stdDevNs: Double,
    val p95Ns: Double,
    val p99Ns: Double,
    val p999Ns: Double,
    val jitterNs: Double,
    val stutterCount: Int,
    val stuttersPerMinute: Double,
    val openingMedianNs: Double,
    val sustainedMedianNs: Double,
    val thermalDegradation: Double,
    val totalDurationNs: Long,
)

@Serializable
public data class FailureEntry(
    val slot: Int,
    val arm: String,
    val label: String,
    val workloadId: String,
    val stage: String,
    val message: String,
)

@Serializable
public data class NullTestEntry(
    val passed: Boolean,
    val driverSha256: String,
    val requiredConsecutivePasses: Int,
    val completedPasses: Int,
    /** The resolution this device demonstrated, as a fraction. 0.05 means 5%. */
    val appliedNoiseFloor: Double,
    val observedNoiseFloor: Double,
    val firstArmWins: Int,
    val orderingBiasP: Double,
    val floorTooCoarse: Boolean,
    val hasOrderingBias: Boolean,
    val explanation: String,
)

@Serializable
public data class IntervalEntry(val point: Double, val low: Double, val high: Double, val confidence: Double)

@Serializable
public data class ComparisonEntry(
    val workloadId: String,
    val labelA: String,
    val labelB: String,
    val verdict: String,
    val reason: String,
    val runsA: Int,
    val runsB: Int,
    val medianANs: Double,
    val medianBNs: Double,
    /** Above 1.0 means A is faster. Same quantity [speedupInterval] brackets. */
    val speedupOfA: Double,
    val percentDifference: Double,
    val speedupInterval: IntervalEntry,
    val pValue: Double,
    val exactTest: Boolean,
    val cliffsDelta: Double,
    val effectMagnitude: String,
    val noiseFloor: Double,
    /** False when the null test has not passed. A ranking cannot be built from these. */
    val trustworthy: Boolean,
    val warnings: List<ComparabilityWarning>,
)
