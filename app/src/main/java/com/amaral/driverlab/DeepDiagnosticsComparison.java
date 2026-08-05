package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

final class DeepDiagnosticsComparison {
    private DeepDiagnosticsComparison() {}

    static JSONObject compare(JSONObject systemPhase, JSONObject candidatePhase) throws Exception {
        JSONObject result = new JSONObject()
                .put("deep_diagnostic_comparison_version", Phase10Contract.COMPARISON_VERSION)
                .put("practical_margin_percent", Phase10Contract.PRACTICAL_MARGIN_PERCENT)
                .put("statistical_significance_claimed", false)
                .put("eligible_for_full_qualification_score", false)
                .put("limitations", Phase10Contract.LIMITATION);
        JSONArray blockers = new JSONArray();
        JSONArray warnings = new JSONArray();
        if (systemPhase == null || candidatePhase == null) {
            return result.put("comparable", false)
                    .put("verdict", "incomplete")
                    .put("blockers", blockers.put("system_or_candidate_result_missing"))
                    .put("warnings", warnings);
        }
        JSONObject system = systemPhase.optJSONObject("native");
        JSONObject candidate = candidatePhase.optJSONObject("native");
        if (system == null || candidate == null
                || !system.optBoolean("success", false)
                || !candidate.optBoolean("success", false)) {
            if (system == null || !system.optBoolean("success", false)) {
                blockers.put("system_diagnostic_failed");
            }
            if (candidate == null || !candidate.optBoolean("success", false)) {
                blockers.put("candidate_diagnostic_failed");
            }
            return result.put("comparable", false)
                    .put("verdict", "failed_execution")
                    .put("blockers", blockers)
                    .put("warnings", warnings);
        }
        String systemMode = system.optString("mode", "");
        String candidateMode = candidate.optString("mode", "");
        if (!systemMode.equals(candidateMode)) blockers.put("diagnostic_mode_mismatch");
        if ("soak".equals(systemMode)) {
            return compareSoak(result, system, candidate, blockers, warnings);
        }

        JSONObject format = compareFormats(system.optJSONObject("format_matrix"),
                candidate.optJSONObject("format_matrix"), blockers);
        JSONObject shader = compareShaderCorpus(system.optJSONObject("shader_pipeline_corpus"),
                candidate.optJSONObject("shader_pipeline_corpus"), blockers, warnings);
        JSONObject memory = compareMemory(system.optJSONObject("memory_pressure"),
                candidate.optJSONObject("memory_pressure"), warnings);
        JSONObject synchronization = compareSynchronization(
                system.optJSONObject("synchronization"),
                candidate.optJSONObject("synchronization"), blockers, warnings);
        JSONObject reliability = compareReliability(system.optJSONObject("reliability_probe"),
                candidate.optJSONObject("reliability_probe"), blockers, warnings);

        boolean comparable = blockers.length() == 0;
        int favorable = countFavorable(shader, memory, synchronization, reliability);
        int unfavorable = countUnfavorable(shader, memory, synchronization, reliability);
        String verdict;
        if (!comparable) verdict = "candidate_blocked";
        else if (favorable >= 2 && unfavorable == 0) verdict = "candidate_improved_descriptive";
        else if (unfavorable >= 2 && favorable == 0) verdict = "candidate_regressed_descriptive";
        else verdict = "mixed_or_equivalent_descriptive";

        return result.put("comparable", comparable)
                .put("verdict", verdict)
                .put("blockers", blockers)
                .put("warnings", warnings)
                .put("format_matrix", format)
                .put("shader_pipeline_corpus", shader)
                .put("memory_pressure", memory)
                .put("synchronization", synchronization)
                .put("reliability_probe", reliability)
                .put("favorable_category_count", favorable)
                .put("unfavorable_category_count", unfavorable);
    }

    private static JSONObject compareSoak(JSONObject output, JSONObject system,
                                          JSONObject candidate, JSONArray blockers,
                                          JSONArray warnings) throws Exception {
        JSONObject left = system.optJSONObject("soak");
        JSONObject right = candidate.optJSONObject("soak");
        if (left == null || right == null) blockers.put("soak_payload_missing");
        if (left != null && left.optBoolean("passed", false)
                && (right == null || !right.optBoolean("passed", false))) {
            blockers.put("candidate_soak_failed");
        }
        double systemP99 = metric(left, "cycle_p99_ms");
        double candidateP99 = metric(right, "cycle_p99_ms");
        double change = lowerBetterChange(systemP99, candidateP99);
        String direction = direction(change);
        if (left != null && right != null && right.optInt("completed_cycles", 0)
                < left.optInt("completed_cycles", 0)) {
            warnings.put("candidate_completed_fewer_soak_cycles");
        }
        return output.put("comparable", blockers.length() == 0)
                .put("verdict", blockers.length() > 0 ? "candidate_blocked"
                        : "better".equals(direction) ? "candidate_improved_descriptive"
                        : "worse".equals(direction) ? "candidate_regressed_descriptive"
                        : "mixed_or_equivalent_descriptive")
                .put("blockers", blockers)
                .put("warnings", warnings)
                .put("soak", new JSONObject()
                        .put("system_completed_cycles", left == null ? 0
                                : left.optInt("completed_cycles", 0))
                        .put("candidate_completed_cycles", right == null ? 0
                                : right.optInt("completed_cycles", 0))
                        .put("candidate_vs_system_p99_percent", finiteOrNull(change))
                        .put("direction", direction));
    }

    private static JSONObject compareFormats(JSONObject system, JSONObject candidate,
                                             JSONArray blockers) throws Exception {
        Map<String, JSONObject> left = formatsByName(system);
        Map<String, JSONObject> right = formatsByName(candidate);
        JSONArray regressions = new JSONArray();
        JSONArray gains = new JSONArray();
        String[] critical = {
                "optimal_sampled", "optimal_color_attachment",
                "optimal_depth_stencil_attachment", "optimal_storage_image",
                "sampled_image_supported", "attachment_image_supported",
                "storage_image_supported"
        };
        for (Map.Entry<String, JSONObject> entry : left.entrySet()) {
            JSONObject candidateFormat = right.get(entry.getKey());
            if (candidateFormat == null) {
                regressions.put(entry.getKey() + ":format_missing");
                continue;
            }
            for (String key : critical) {
                boolean a = entry.getValue().optBoolean(key, false);
                boolean b = candidateFormat.optBoolean(key, false);
                if (a && !b) regressions.put(entry.getKey() + ":" + key);
                if (!a && b) gains.put(entry.getKey() + ":" + key);
            }
        }
        if (regressions.length() > 0) blockers.put("format_capability_regression");
        return new JSONObject()
                .put("system_format_count", left.size())
                .put("candidate_format_count", right.size())
                .put("regression_count", regressions.length())
                .put("gain_count", gains.length())
                .put("regressions", regressions)
                .put("gains", gains);
    }

    private static Map<String, JSONObject> formatsByName(JSONObject matrix) {
        Map<String, JSONObject> output = new LinkedHashMap<>();
        if (matrix == null) return output;
        JSONArray formats = matrix.optJSONArray("formats");
        if (formats == null) return output;
        for (int index = 0; index < formats.length(); ++index) {
            JSONObject item = formats.optJSONObject(index);
            if (item != null) output.put(item.optString("format", "#" + index), item);
        }
        return output;
    }

    private static JSONObject compareShaderCorpus(JSONObject system, JSONObject candidate,
                                                  JSONArray blockers,
                                                  JSONArray warnings) throws Exception {
        if (system == null || candidate == null) {
            blockers.put("shader_corpus_missing");
            return new JSONObject().put("direction", "unavailable");
        }
        int systemSuccess = system.optInt("successful_cases", 0);
        int candidateSuccess = candidate.optInt("successful_cases", 0);
        if (candidateSuccess < systemSuccess) blockers.put("shader_corpus_regression");
        if (candidate.optLong("pipeline_cache_serialized_bytes", 0L) == 0L
                && system.optLong("pipeline_cache_serialized_bytes", 0L) > 0L) {
            warnings.put("candidate_pipeline_cache_not_serialized");
        }
        double coldChange = lowerBetterChange(
                metric(system, "cold_pipeline_total_ms"),
                metric(candidate, "cold_pipeline_total_ms"));
        double warmChange = lowerBetterChange(
                metric(system, "warm_pipeline_total_ms"),
                metric(candidate, "warm_pipeline_total_ms"));
        return new JSONObject()
                .put("system_successful_cases", systemSuccess)
                .put("candidate_successful_cases", candidateSuccess)
                .put("cold_pipeline_change_percent", finiteOrNull(coldChange))
                .put("warm_pipeline_change_percent", finiteOrNull(warmChange))
                .put("direction", combinedDirection(coldChange, warmChange));
    }

    private static JSONObject compareMemory(JSONObject system, JSONObject candidate,
                                            JSONArray warnings) throws Exception {
        if (system == null || candidate == null) {
            warnings.put("memory_pressure_missing");
            return new JSONObject().put("direction", "unavailable");
        }
        double systemPeak = metric(system, "peak_allocated_bytes");
        double candidatePeak = metric(candidate, "peak_allocated_bytes");
        double peakChange = higherBetterChange(systemPeak, candidatePeak);
        if (system.optBoolean("completed_safe_target", false)
                && !candidate.optBoolean("completed_safe_target", false)) {
            warnings.put("candidate_did_not_complete_memory_safe_target");
        }
        double durationChange = lowerBetterChange(metric(system, "duration_ms"),
                metric(candidate, "duration_ms"));
        return new JSONObject()
                .put("peak_allocated_change_percent", finiteOrNull(peakChange))
                .put("duration_change_percent", finiteOrNull(durationChange))
                .put("direction", combinedDirection(peakChange, durationChange));
    }

    private static JSONObject compareSynchronization(JSONObject system, JSONObject candidate,
                                                     JSONArray blockers,
                                                     JSONArray warnings) throws Exception {
        if (system == null || candidate == null) {
            blockers.put("synchronization_payload_missing");
            return new JSONObject().put("direction", "unavailable");
        }
        if (system.optBoolean("passed", false) && !candidate.optBoolean("passed", false)) {
            blockers.put("candidate_synchronization_failure");
        }
        if (!candidate.optBoolean("timeline_semaphore_executed", false)) {
            warnings.put("timeline_semaphore_not_executed_in_v1");
        }
        double p99Change = lowerBetterChange(
                metric(system, "fence_submit_wait_p99_ms"),
                metric(candidate, "fence_submit_wait_p99_ms"));
        return new JSONObject()
                .put("candidate_vs_system_fence_p99_percent", finiteOrNull(p99Change))
                .put("system_passed", system.optBoolean("passed", false))
                .put("candidate_passed", candidate.optBoolean("passed", false))
                .put("direction", direction(p99Change));
    }

    private static JSONObject compareReliability(JSONObject system, JSONObject candidate,
                                                 JSONArray blockers,
                                                 JSONArray warnings) throws Exception {
        if (system == null || candidate == null) {
            warnings.put("reliability_probe_missing");
            return new JSONObject().put("direction", "unavailable");
        }
        if (system.optBoolean("passed", false) && !candidate.optBoolean("passed", false)) {
            blockers.put("candidate_reliability_probe_failed");
        }
        double p99Change = lowerBetterChange(metric(system, "cycle_p99_ms"),
                metric(candidate, "cycle_p99_ms"));
        return new JSONObject()
                .put("system_completed_cycles", system.optInt("completed_cycles", 0))
                .put("candidate_completed_cycles", candidate.optInt("completed_cycles", 0))
                .put("candidate_vs_system_cycle_p99_percent", finiteOrNull(p99Change))
                .put("direction", direction(p99Change));
    }

    private static int countFavorable(JSONObject... categories) {
        int count = 0;
        for (JSONObject category : categories) {
            if (category != null && "better".equals(category.optString("direction"))) count++;
        }
        return count;
    }

    private static int countUnfavorable(JSONObject... categories) {
        int count = 0;
        for (JSONObject category : categories) {
            if (category != null && "worse".equals(category.optString("direction"))) count++;
        }
        return count;
    }

    private static String combinedDirection(double first, double second) {
        int favorable = 0;
        int unfavorable = 0;
        for (double value : new double[]{first, second}) {
            String direction = direction(value);
            if ("better".equals(direction)) favorable++;
            if ("worse".equals(direction)) unfavorable++;
        }
        if (favorable > 0 && unfavorable == 0) return "better";
        if (unfavorable > 0 && favorable == 0) return "worse";
        return "equivalent_or_mixed";
    }

    private static String direction(double improvementPercent) {
        if (!Double.isFinite(improvementPercent)) return "unavailable";
        if (improvementPercent > Phase10Contract.PRACTICAL_MARGIN_PERCENT) return "better";
        if (improvementPercent < -Phase10Contract.PRACTICAL_MARGIN_PERCENT) return "worse";
        return "equivalent_or_mixed";
    }

    private static double lowerBetterChange(double system, double candidate) {
        if (!(system > 0.0) || !(candidate > 0.0)) return Double.NaN;
        return (system / candidate - 1.0) * 100.0;
    }

    private static double higherBetterChange(double system, double candidate) {
        if (!(system > 0.0) || !(candidate > 0.0)) return Double.NaN;
        return (candidate / system - 1.0) * 100.0;
    }

    private static double metric(JSONObject object, String key) {
        return object == null ? Double.NaN : object.optDouble(key, Double.NaN);
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }
}
