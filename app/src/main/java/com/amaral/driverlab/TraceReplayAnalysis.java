package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

final class TraceReplayAnalysis {
    private TraceReplayAnalysis() {}

    static JSONObject analyze(JSONArray phases, int expectedRounds, int mode) throws Exception {
        JSONArray comparisons = new JSONArray();
        Set<String> systemHashes = new HashSet<>();
        Set<String> candidateHashes = new HashSet<>();
        int completePairs = 0;
        int mismatchCount = 0;
        int failedPhases = 0;

        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) {
                failedPhases++;
                continue;
            }
            String hash = evidenceHash(phase);
            if (hash.isEmpty()) {
                failedPhases++;
                continue;
            }
            if ("custom".equals(phase.optString("driver_mode"))) candidateHashes.add(hash);
            else systemHashes.add(hash);
        }

        if (mode == RunCoordinator.MODE_AB) {
            for (int round = 1; round <= expectedRounds; ++round) {
                JSONObject system = find(phases, round, false);
                JSONObject candidate = find(phases, round, true);
                String systemHash = evidenceHash(system);
                String candidateHash = evidenceHash(candidate);
                if (systemHash.isEmpty() || candidateHash.isEmpty()) continue;
                boolean match = systemHash.equals(candidateHash);
                comparisons.put(new JSONObject()
                        .put("round", round)
                        .put("system_sha256", systemHash)
                        .put("candidate_sha256", candidateHash)
                        .put("match", match));
                completePairs++;
                if (!match) mismatchCount++;
            }
        }

        boolean systemNondeterministic = systemHashes.size() > 1;
        boolean candidateNondeterministic = candidateHashes.size() > 1;
        boolean comparisonAvailable = mode == RunCoordinator.MODE_AB && completePairs > 0;
        boolean passed = comparisonAvailable
                && completePairs == expectedRounds
                && mismatchCount == 0
                && failedPhases == 0
                && !systemNondeterministic
                && !candidateNondeterministic;

        return new JSONObject()
                .put("trace_analysis_version", TraceReplayContract.TRACE_ANALYSIS_VERSION)
                .put("comparison_available", comparisonAvailable)
                .put("expected_pair_count", mode == RunCoordinator.MODE_AB ? expectedRounds : 0)
                .put("complete_pair_count", completePairs)
                .put("output_mismatch_count", mismatchCount)
                .put("failed_phase_count", failedPhases)
                .put("system_unique_output_hashes", toArray(systemHashes))
                .put("candidate_unique_output_hashes", toArray(candidateHashes))
                .put("system_nondeterministic", systemNondeterministic)
                .put("candidate_nondeterministic", candidateNondeterministic)
                .put("comparisons", comparisons)
                .put("passed_correctness_gate", passed)
                .put("comparison_policy", "exact_sha256_per_paired_round")
                .put("limitations", TraceReplayContract.LIMITATION);
    }

    static String verdictFor(JSONObject trace, JSONObject statistics, int mode) {
        if (trace.optInt("failed_phase_count", 0) > 0) return "failed_trace_replay_execution";
        if (trace.optBoolean("system_nondeterministic", false)
                || trace.optBoolean("candidate_nondeterministic", false)) {
            return "failed_trace_nondeterminism";
        }
        if (mode != RunCoordinator.MODE_AB) return "completed_single_driver_trace_replay";
        if (!trace.optBoolean("comparison_available", false)) return "insufficient_trace_reference";
        if (!trace.optBoolean("passed_correctness_gate", false)) return "failed_trace_output_mismatch";
        return StatisticalComparison.verdictFor(statistics, 0);
    }

    private static JSONObject find(JSONArray phases, int round, boolean custom) {
        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            if (phase.optInt("round", -1) != round) continue;
            if (custom == "custom".equals(phase.optString("driver_mode"))) return phase;
        }
        return null;
    }

    private static String evidenceHash(JSONObject phase) {
        if (phase == null) return "";
        JSONObject evidence = phase.optJSONObject("evidence");
        if (evidence == null) return "";
        String value = evidence.optString("sha256_output", "").toLowerCase();
        return value.matches("[0-9a-f]{64}") ? value : "";
    }

    private static JSONArray toArray(Set<String> values) {
        JSONArray output = new JSONArray();
        values.stream().sorted().forEach(output::put);
        return output;
    }
}
