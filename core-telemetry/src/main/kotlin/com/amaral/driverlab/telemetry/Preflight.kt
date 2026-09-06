package com.amaral.driverlab.telemetry

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One reason the device is not in a fit state to be measured. */
@Serializable
public data class PreflightIssue(
    val code: Code,
    val message: String,
    /**
     * Whether the user may proceed anyway. A blocking issue can still be
     * overridden — see [PreflightReport.overridden] — but the override is recorded
     * in the result and travels with it to the leaderboard.
     */
    val blocking: Boolean,
) {
    @Serializable
    public enum class Code {
        BATTERY_TOO_LOW,
        PLUGGED_IN,
        ALREADY_THROTTLING,
        DEVICE_TOO_HOT,
        NOT_COOLED_DOWN,
        NO_THERMAL_TELEMETRY,
    }
}

@Serializable
public data class PreflightPolicy(
    val minimumBatteryPercent: Int = 40,
    /**
     * Charging pins clocks and adds heat the run did not cause. Section 5 asks for
     * the charger to be out, and this is the one check most likely to be overridden
     * on a handheld that is awkward to unplug — hence it is recorded, not silent.
     */
    val requireUnplugged: Boolean = true,
    val requireThermalStatusNone: Boolean = true,
    val maximumStartCelsius: Double = 45.0,
    val minimumCooldownMs: Long = 5 * 60 * 1000L,
)

@Serializable
public data class PreflightReport(
    val issues: List<PreflightIssue>,
    val overridden: Boolean = false,
    val policy: PreflightPolicy = PreflightPolicy(),
) {
    public val blockingIssues: List<PreflightIssue> = issues.filter { it.blocking }

    /** True when the run may start: nothing blocking, or the user chose to proceed. */
    public val clearedToRun: Boolean = blockingIssues.isEmpty() || overridden

    /**
     * A run that started with blocking issues overridden is still a real run, and
     * its numbers are still real — they just describe a device in a state nobody
     * else can reproduce. The ingestion pipeline rejects these rather than ranking
     * them, and this flag is what it looks at.
     */
    public val lowConfidence: Boolean = blockingIssues.isNotEmpty() && overridden

    public fun summary(): String = when {
        issues.isEmpty() -> "Device is ready: battery, charger and thermal state all check out."
        clearedToRun && blockingIssues.isEmpty() ->
            "Ready, with notes: " + issues.joinToString("; ") { it.message }
        clearedToRun -> "Started anyway, overriding: " + blockingIssues.joinToString("; ") { it.message }
        else -> "Not ready: " + blockingIssues.joinToString("; ") { it.message }
    }
}

/**
 * The pre-run checklist from section 5.
 *
 * Kept free of Android types so the rules can be tested directly rather than
 * through a device. A rule nobody can test is a rule that quietly stops working.
 */
public object Preflight {

    public fun evaluate(
        sample: TelemetrySample,
        millisSinceLastRun: Long?,
        policy: PreflightPolicy = PreflightPolicy(),
    ): PreflightReport {
        val issues = mutableListOf<PreflightIssue>()

        val level = sample.battery.levelPercent
        if (level in 0 until policy.minimumBatteryPercent) {
            issues += PreflightIssue(
                PreflightIssue.Code.BATTERY_TOO_LOW,
                "Battery is at $level%, below the ${policy.minimumBatteryPercent}% this protocol asks " +
                    "for. Below that, Android starts making its own decisions about clocks.",
                blocking = true,
            )
        }

        if (policy.requireUnplugged && sample.battery.plugged) {
            issues += PreflightIssue(
                PreflightIssue.Code.PLUGGED_IN,
                "The charger is connected. Charging pins clocks and adds heat this run did not cause, " +
                    "so the numbers will not match an unplugged run.",
                blocking = true,
            )
        }

        if (policy.requireThermalStatusNone && sample.thermalStatus.throttling) {
            issues += PreflightIssue(
                PreflightIssue.Code.ALREADY_THROTTLING,
                "Android reports thermal status ${sample.thermalStatus}. The device is already being " +
                    "held back, so this would measure the throttle, not the driver.",
                blocking = true,
            )
        }

        val peak = sample.peakZoneCelsius
        if (peak == null) {
            issues += PreflightIssue(
                PreflightIssue.Code.NO_THERMAL_TELEMETRY,
                "No thermal zone could be read on this device. The run can still go ahead, but the " +
                    "report will not be able to show whether it heated up.",
                blocking = false,
            )
        } else if (peak > policy.maximumStartCelsius) {
            issues += PreflightIssue(
                PreflightIssue.Code.DEVICE_TOO_HOT,
                "The hottest sensor reads ${peak.roundToInt()} °C, above the " +
                    "${policy.maximumStartCelsius.roundToInt()} °C start limit. Let it cool.",
                blocking = true,
            )
        }

        if (millisSinceLastRun != null && millisSinceLastRun < policy.minimumCooldownMs) {
            val minutes = (policy.minimumCooldownMs - millisSinceLastRun) / 60000.0
            issues += PreflightIssue(
                PreflightIssue.Code.NOT_COOLED_DOWN,
                "The last run finished less than ${policy.minimumCooldownMs / 60000} minutes ago. " +
                    "About ${maxOf(1, minutes.roundToInt())} more minutes of cooling would make this " +
                    "run comparable with it.",
                blocking = true,
            )
        }

        return PreflightReport(issues = issues, overridden = false, policy = policy)
    }
}
