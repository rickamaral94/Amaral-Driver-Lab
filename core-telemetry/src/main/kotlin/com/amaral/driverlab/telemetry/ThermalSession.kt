package com.amaral.driverlab.telemetry

import kotlinx.serialization.Serializable

/**
 * The thermal session a set of runs belongs to (P7).
 *
 * Two results are directly comparable only when they came from the same session:
 * same power state, no cooldown gap long enough to change the starting point, and
 * no throttling event in between. Anything else is comparable only with a warning
 * attached, and the warning has to survive into the published report — which is
 * why the session id is part of a run rather than a UI decision.
 */
@Serializable
public data class ThermalSession(
    val id: String,
    val startedAtEpochMs: Long,
    val openingSample: TelemetrySample,
) {
    /**
     * Whether [other] can be compared with this session without a caveat.
     *
     * Deliberately strict. The cost of a false "comparable" is a published ranking
     * built on thermal drift; the cost of a false "not comparable" is a warning
     * the user can read and dismiss.
     */
    public fun comparableWith(other: ThermalSession): Boolean = id == other.id

    public companion object {
        /** Past this gap, the device has been doing something else and the session is over. */
        public const val MAXIMUM_SESSION_GAP_MS: Long = 30 * 60 * 1000L
    }
}

/** Why a comparison is being shown with reduced confidence. */
@Serializable
public enum class ComparabilityWarning {
    DIFFERENT_THERMAL_SESSION,
    THROTTLED_DURING_RUN,
    PREFLIGHT_OVERRIDDEN,
    DIFFERENT_WORKLOAD_SPEC,
    DIFFERENT_DEVICE,
    NULL_TEST_NOT_PASSED,
}

public object Comparability {

    /**
     * @return the warnings that must be shown alongside a comparison, in the order
     *   they should be read. An empty list means the comparison stands on its own.
     */
    public fun check(
        left: RunContext,
        right: RunContext,
        nullTestPassed: Boolean,
    ): List<ComparabilityWarning> = buildList {
        if (!nullTestPassed) add(ComparabilityWarning.NULL_TEST_NOT_PASSED)
        if (left.deviceFingerprint != right.deviceFingerprint) add(ComparabilityWarning.DIFFERENT_DEVICE)
        if (left.workloadSpecFingerprint != right.workloadSpecFingerprint) {
            add(ComparabilityWarning.DIFFERENT_WORKLOAD_SPEC)
        }
        if (left.thermalSessionId != right.thermalSessionId) {
            add(ComparabilityWarning.DIFFERENT_THERMAL_SESSION)
        }
        if (left.throttledDuringRun || right.throttledDuringRun) {
            add(ComparabilityWarning.THROTTLED_DURING_RUN)
        }
        if (left.preflightOverridden || right.preflightOverridden) {
            add(ComparabilityWarning.PREFLIGHT_OVERRIDDEN)
        }
    }
}

/** The minimum a run has to carry for [Comparability] to judge it. */
@Serializable
public data class RunContext(
    val thermalSessionId: String,
    val deviceFingerprint: String,
    val workloadSpecFingerprint: String,
    val throttledDuringRun: Boolean,
    val preflightOverridden: Boolean,
)
