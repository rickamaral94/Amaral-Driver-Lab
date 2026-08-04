package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

final class TelemetryComparison {
    private TelemetryComparison() {}

    static JSONObject compare(TelemetrySessionRecord first,
                              TelemetrySessionRecord second) throws Exception {
        TelemetrySessionRecord system = "system".equals(first.driverMode) ? first
                : "system".equals(second.driverMode) ? second : null;
        TelemetrySessionRecord candidate = "custom".equals(first.driverMode) ? first
                : "custom".equals(second.driverMode) ? second : null;
        JSONArray checks = new JSONArray();
        boolean armsAvailable = system != null && candidate != null;
        checks.put(check("one_system_one_candidate", armsAvailable,
                first.driverMode, second.driverMode));
        if (!armsAvailable) {
            return base(first, second, checks)
                    .put("available", false)
                    .put("historically_comparable", false)
                    .put("classification", "requires_system_and_candidate_sessions")
                    .put("limitations", Phase9Contract.LIMITATION);
        }

        boolean exactKey = system.comparisonKey().equals(candidate.comparisonKey());
        checks.put(check("versioned_comparison_key", exactKey,
                system.comparisonKey(), candidate.comparisonKey()));
        JSONObject systemFrame = system.summary.getJSONObject("frame");
        JSONObject candidateFrame = candidate.summary.getJSONObject("frame");
        int systemSamples = systemFrame.optInt("sample_count", 0);
        int candidateSamples = candidateFrame.optInt("sample_count", 0);
        long systemDuration = system.summary.optLong("duration_ms", 0L);
        long candidateDuration = candidate.summary.optLong("duration_ms", 0L);
        double durationDifference = percentDifference(candidateDuration, systemDuration);
        double sampleDifference = percentDifference(candidateSamples, systemSamples);
        boolean enoughFrames = systemSamples >= 120 && candidateSamples >= 120;
        boolean durationClose = Double.isFinite(durationDifference)
                && Math.abs(durationDifference) <= 10.0;
        boolean sampleCountClose = Double.isFinite(sampleDifference)
                && Math.abs(sampleDifference) <= 10.0;
        checks.put(check("minimum_120_frame_samples_per_arm", enoughFrames,
                systemSamples, candidateSamples));
        checks.put(check("duration_within_10_percent", durationClose,
                systemDuration, candidateDuration));
        checks.put(check("sample_count_within_10_percent", sampleCountClose,
                systemSamples, candidateSamples));
        boolean comparable = exactKey && enoughFrames && durationClose && sampleCountClose;

        double systemP50 = number(systemFrame, "p50_frame_ms");
        double candidateP50 = number(candidateFrame, "p50_frame_ms");
        double systemP95 = number(systemFrame, "p95_frame_ms");
        double candidateP95 = number(candidateFrame, "p95_frame_ms");
        double systemP99 = number(systemFrame, "p99_frame_ms");
        double candidateP99 = number(candidateFrame, "p99_frame_ms");
        double systemLow = number(systemFrame, "one_percent_low_fps");
        double candidateLow = number(candidateFrame, "one_percent_low_fps");
        double systemStutter = number(systemFrame, "stutter_ratio_over_25_ms");
        double candidateStutter = number(candidateFrame, "stutter_ratio_over_25_ms");
        JSONObject systemEvents = system.summary.getJSONObject("events");
        JSONObject candidateEvents = candidate.summary.getJSONObject("events");
        int extraCrashes = candidateEvents.optInt("crash_count", 0)
                - systemEvents.optInt("crash_count", 0);
        int extraDeviceLost = candidateEvents.optInt("device_lost_count", 0)
                - systemEvents.optInt("device_lost_count", 0);
        int extraFatal = candidateEvents.optInt("fatal_event_count", 0)
                - systemEvents.optInt("fatal_event_count", 0);

        double p99Improvement = improvementLowerIsBetter(systemP99, candidateP99);
        double lowImprovement = improvementHigherIsBetter(systemLow, candidateLow);
        double stutterChangePp = Double.isFinite(systemStutter)
                && Double.isFinite(candidateStutter)
                ? (candidateStutter - systemStutter) * 100.0 : Double.NaN;
        String classification;
        if (!comparable) {
            classification = "not_historically_comparable";
        } else if (extraCrashes > 0 || extraDeviceLost > 0 || extraFatal > 0) {
            classification = "candidate_regressed_stability";
        } else if (p99Improvement > Phase9Contract.DESCRIPTIVE_MARGIN_PERCENT
                && lowImprovement >= -Phase9Contract.DESCRIPTIVE_MARGIN_PERCENT
                && (!Double.isFinite(stutterChangePp) || stutterChangePp <= 0.0)) {
            classification = "candidate_better_descriptive";
        } else if (p99Improvement < -Phase9Contract.DESCRIPTIVE_MARGIN_PERCENT) {
            classification = "system_better_descriptive";
        } else {
            classification = "mixed_or_equivalent_descriptive";
        }

        return base(first, second, checks)
                .put("available", true)
                .put("historically_comparable", comparable)
                .put("system_session_id", system.sessionId)
                .put("candidate_session_id", candidate.sessionId)
                .put("comparison_key", system.comparisonKey())
                .put("duration_difference_percent", nullable(durationDifference))
                .put("sample_count_difference_percent", nullable(sampleDifference))
                .put("metrics", new JSONObject()
                        .put("p50_frame_ms", metric(systemP50, candidateP50, true))
                        .put("p95_frame_ms", metric(systemP95, candidateP95, true))
                        .put("p99_frame_ms", metric(systemP99, candidateP99, true))
                        .put("one_percent_low_fps", metric(systemLow, candidateLow, false))
                        .put("stutter_ratio_change_percentage_points",
                                nullable(stutterChangePp)))
                .put("candidate_extra_crashes", extraCrashes)
                .put("candidate_extra_device_lost", extraDeviceLost)
                .put("candidate_extra_fatal_events", extraFatal)
                .put("p99_improvement_percent", nullable(p99Improvement))
                .put("one_percent_low_improvement_percent", nullable(lowImprovement))
                .put("classification", classification)
                .put("statistical_inference_available", false)
                .put("included_in_full_qualification_score", false)
                .put("limitations", Phase9Contract.LIMITATION);
    }

    private static JSONObject base(TelemetrySessionRecord first,
                                   TelemetrySessionRecord second,
                                   JSONArray checks) throws Exception {
        return new JSONObject()
                .put("telemetry_comparison_version",
                        Phase9Contract.TELEMETRY_COMPARISON_VERSION)
                .put("left_session", first.compactJson())
                .put("right_session", second.compactJson())
                .put("comparability_checks", checks);
    }

    private static JSONObject check(String name, boolean passed,
                                    Object left, Object right) throws Exception {
        return new JSONObject()
                .put("check", name)
                .put("passed", passed)
                .put("left", left == null ? JSONObject.NULL : left)
                .put("right", right == null ? JSONObject.NULL : right);
    }

    private static JSONObject metric(double system, double candidate,
                                     boolean lowerIsBetter) throws Exception {
        double improvement = lowerIsBetter
                ? improvementLowerIsBetter(system, candidate)
                : improvementHigherIsBetter(system, candidate);
        return new JSONObject()
                .put("system", nullable(system))
                .put("candidate", nullable(candidate))
                .put("candidate_improvement_percent", nullable(improvement));
    }

    private static double number(JSONObject object, String key) {
        return object.optDouble(key, Double.NaN);
    }

    private static double percentDifference(double value, double reference) {
        if (!Double.isFinite(value) || !Double.isFinite(reference) || reference == 0.0) {
            return Double.NaN;
        }
        return (value - reference) * 100.0 / Math.abs(reference);
    }

    private static double improvementLowerIsBetter(double system, double candidate) {
        if (!Double.isFinite(system) || !Double.isFinite(candidate) || system <= 0.0) {
            return Double.NaN;
        }
        return (system - candidate) * 100.0 / system;
    }

    private static double improvementHigherIsBetter(double system, double candidate) {
        if (!Double.isFinite(system) || !Double.isFinite(candidate) || system <= 0.0) {
            return Double.NaN;
        }
        return (candidate - system) * 100.0 / system;
    }

    private static Object nullable(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }
}
