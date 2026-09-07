package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.NullTest
import com.amaral.driverlab.stats.NullTestResult
import kotlinx.serialization.Serializable
import java.io.File

/** One A/A comparison: the per-run medians each arm produced, in the order they ran. */
@Serializable
public data class ArmPair(val first: List<Double>, val second: List<Double>) {
    internal fun toArrays(): Pair<DoubleArray, DoubleArray> =
        first.toDoubleArray() to second.toDoubleArray()
}

/**
 * The A/A comparisons for one workload, in the order they were acquired.
 *
 * One pool, not a calibration half and a test half. Each comparison is judged against a
 * floor calibrated on the others, so none judges itself and the device runs ten comparisons
 * instead of twenty — see [com.amaral.driverlab.stats.NullTest.crossValidated].
 *
 * The order is part of the data. The sign test reads the direction of each comparison from
 * it, so a reader that sorts or shuffles this list destroys the only evidence of an ordering
 * effect.
 */
@Serializable
public data class WorkloadArms(
    val workloadId: String,
    val comparisons: List<ArmPair>,
)

/**
 * What a device demonstrated about its own resolution, stored so the next run can ask.
 *
 * Only the **raw run medians** are kept. The verdict, the floor and the p values are
 * re-derived on every read by the same code that produced them the first time. Storing
 * `passed: true` would make the file the authority on whether ranking is allowed, and a
 * file is a thing a user can edit; storing the series means the claim can only be as good
 * as the measurement behind it. It is the same rule the ingestion pipeline applies to
 * published reports, pointed at ourselves.
 *
 * Keyed by device rather than by driver: the null test measures whether *this hardware*
 * can tell a driver apart from itself. [driverSha256] records which driver was the vehicle
 * so a reader can reproduce the run, not because a different driver would need its own.
 */
@Serializable
public data class NullTestRecord(
    val recordVersion: Int = CURRENT_VERSION,
    val deviceFingerprint: String,
    val driverSha256: String,
    val driverLabel: String,
    val appVersion: String,
    val completedAtEpochMs: Long,
    val runsPerArm: Int,
    val perWorkload: List<WorkloadArms>,
) {

    /** Re-derives the verdict for every workload the test covered. */
    public fun evaluate(config: AbConfig = AbConfig()): Map<String, NullTestResult> =
        perWorkload.associate { workload ->
            workload.workloadId to NullTest.crossValidated(
                driverSha256 = driverSha256,
                arms = workload.comparisons.map { it.toArrays() },
                config = config,
            )
        }

    /**
     * The verdict for a whole profile: a device has proved itself only on the workloads it
     * actually ran. A profile containing a workload the null test never covered is not
     * gated by this record, so the answer is `null` rather than a pass.
     */
    public fun resultFor(workloadIds: Collection<String>, config: AbConfig = AbConfig()): ProfileVerdict? {
        val derived = evaluate(config)
        val covered = workloadIds.associateWith { derived[it] ?: return null }
        return ProfileVerdict(this, covered)
    }

    public companion object {
        /**
         * 2 since the protocol became one cross-validated pool. A version 1 record split its
         * comparisons into a calibration half and a test half and cannot be reinterpreted as
         * a pool: the halves were judged under different rules, so reading them together
         * would attribute a verdict to measurements that never supported it. Old records are
         * ignored rather than migrated.
         */
        public const val CURRENT_VERSION: Int = 2
    }
}

/**
 * A record read back against the workloads a run is about to use.
 *
 * Passing means *every* covered workload passed. A device that resolves the baseline
 * workload but not the tiling one has not earned a ranking on the tiling one, and one
 * aggregate "passed" would hide exactly that.
 */
public data class ProfileVerdict(
    val record: NullTestRecord,
    val perWorkload: Map<String, NullTestResult>,
) {
    public val passed: Boolean = perWorkload.isNotEmpty() && perWorkload.values.all { it.passed }

    /** The floor to judge a given workload's comparison against. */
    public fun noiseFloorFor(workloadId: String): Double? =
        perWorkload[workloadId]?.appliedNoiseFloor

    /** The worst offender, which is the one worth putting in front of the user. */
    public fun weakest(): NullTestResult? =
        perWorkload.values.firstOrNull { !it.passed } ?: perWorkload.values.firstOrNull()

    public fun explain(): String = perWorkload.entries
        .joinToString("\n\n") { (workloadId, result) -> "$workloadId — ${result.explain()}" }
}

/**
 * Where the null test record lives on the device.
 *
 * One record per device, replaced when a newer test finishes. A stored record whose
 * fingerprint does not match the machine reading it is ignored rather than migrated —
 * a floor measured on other hardware says nothing about this one.
 *
 * The record is deliberately *not* tied to a thermal session, and cannot be: it is a
 * separate, much longer run. So the floor it carries is an estimate transported across
 * sessions, which is sound for a device whose behaviour is stable and is exactly what the
 * test checks. It is not a substitute for P7 within a comparison.
 */
public class NullTestStore(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)

    public fun write(record: NullTestRecord) {
        directory.mkdirs()
        // Written to a sibling and moved, so an interrupted write cannot leave behind a
        // half-record that reads as a device with no null test at all.
        val staging = File(directory, "$FILE_NAME.tmp")
        staging.writeText(ReportJson.encode(record))
        if (!staging.renameTo(file)) {
            file.writeText(staging.readText())
            staging.delete()
        }
    }

    /** @return the stored record, or null when there is none, it is unreadable, or it
     *   belongs to different hardware. */
    public fun read(deviceFingerprint: String): NullTestRecord? {
        val stored = runCatching { ReportJson.decodeNullTestRecord(file.readText()) }.getOrNull()
            ?: return null
        if (stored.recordVersion != NullTestRecord.CURRENT_VERSION) return null
        if (stored.deviceFingerprint != deviceFingerprint) return null
        return stored
    }

    public fun clear() {
        file.delete()
    }

    public companion object {
        public const val FILE_NAME: String = "null-test.json"
    }
}
