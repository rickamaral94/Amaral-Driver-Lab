package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class QualificationScore {
    private QualificationScore() {}

    static JSONObject evaluate(JSONObject profile, JSONArray completedSteps,
                               JSONObject preflight, JSONObject environmentComparison)
            throws Exception {
        if (!QualificationProfile.verify(profile)) {
            throw new IllegalArgumentException("Perfil Full Qualification inválido");
        }
        int profileVersion = profile.getInt("profile_version");
        int minimumValidSteps = profileVersion >= 3
                ? Phase11Contract.MINIMUM_VALID_PERFORMANCE_CATEGORIES
                : profileVersion >= 2 ? Phase8Contract.MINIMUM_VALID_PERFORMANCE_STEPS_V2
                : Phase7Contract.MINIMUM_VALID_PERFORMANCE_STEPS;
        Map<String, JSONObject> byStep = new HashMap<>();
        for (int index = 0; index < completedSteps.length(); ++index) {
            JSONObject item = completedSteps.optJSONObject(index);
            if (item != null) byStep.put(item.optString("step_id"), item);
        }

        JSONArray gateReasons = new JSONArray();
        JSONArray warnings = new JSONArray();
        copyArray(preflight.optJSONObject("evaluation"), "blockers", gateReasons);
        copyArray(preflight.optJSONObject("evaluation"), "warnings", warnings);
        copyArray(environmentComparison, "blockers", gateReasons);
        copyArray(environmentComparison, "warnings", warnings);

        List<JSONObject> categories = new ArrayList<>();
        double[] weightedImprovement = {0.0};
        double[] weightedScore = {0.0};
        int[] validWeight = {0};
        int[] validPerformanceSteps = {0};
        int[] conclusiveSteps = {0};
        int[] candidateWins = {0};
        int[] systemWins = {0};
        int[] ties = {0};
        int compatibilityChecksTotal = 0;
        int compatibilityChecksPassed = 0;

        JSONArray profileSteps = profile.getJSONArray("steps");
        for (int index = 0; index < profileSteps.length(); ++index) {
            JSONObject step = profileSteps.getJSONObject(index);
            String stepId = step.getString("step_id");
            String kind = step.optString("step_kind", QualificationProfile.KIND_SUITE);
            JSONObject completed = byStep.get(stepId);
            if (QualificationProfile.KIND_DEEP_DIAGNOSTICS.equals(kind)) {
                int[] checks = evaluateDeepDiagnostics(step, completed, categories, gateReasons,
                        warnings, weightedImprovement, weightedScore, validWeight,
                        validPerformanceSteps, conclusiveSteps, candidateWins, systemWins, ties);
                compatibilityChecksTotal += checks[0];
                compatibilityChecksPassed += checks[1];
                continue;
            }
            if (QualificationProfile.KIND_SHORT_SOAK.equals(kind)) {
                int[] checks = evaluateShortSoak(step, completed, categories, gateReasons, warnings);
                compatibilityChecksTotal += checks[0];
                compatibilityChecksPassed += checks[1];
                continue;
            }

            int weight = step.optInt("score_weight", 0);
            boolean gate = step.optBoolean("compatibility_gate", false);
            JSONObject category = new JSONObject()
                    .put("step_id", stepId)
                    .put("label", step.optString("label"))
                    .put("workload_id", step.optString("workload_id"))
                    .put("trace_id", step.isNull("trace_id") ? JSONObject.NULL : step.opt("trace_id"))
                    .put("weight", weight)
                    .put("evidence_type", "paired_suite");

            if (completed == null || !"completed".equals(completed.optString("status"))) {
                category.put("status", completed == null ? "missing" : completed.optString("status"))
                        .put("improvement_percent", JSONObject.NULL)
                        .put("normalized_score", JSONObject.NULL)
                        .put("classification", "unavailable");
                gateReasons.put("missing_or_failed_step:" + stepId);
                if (gate) compatibilityChecksTotal++;
                categories.add(category);
                continue;
            }
            JSONObject report = completed.optJSONObject("report");
            if (report == null) {
                category.put("status", "invalid_report")
                        .put("improvement_percent", JSONObject.NULL)
                        .put("normalized_score", JSONObject.NULL)
                        .put("classification", "unavailable");
                gateReasons.put("invalid_report:" + stepId);
                if (gate) compatibilityChecksTotal++;
                categories.add(category);
                continue;
            }

            String verdict = report.optString("verdict", "unknown");
            boolean reportFailure = verdict.startsWith("failed_")
                    || report.optJSONArray("failure_catalog") != null
                    && report.optJSONArray("failure_catalog").length() > 0;
            if (reportFailure) gateReasons.put("blocking_suite:" + stepId + ":" + verdict);

            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(step.optString("workload_id"))) {
                compatibilityChecksTotal++;
                boolean passed = "passed_render_correctness".equals(verdict)
                        && report.optJSONObject("render_correctness") != null
                        && report.optJSONObject("render_correctness").optBoolean("passed", false);
                if (passed) compatibilityChecksPassed++;
                category.put("status", passed ? "passed" : "failed")
                        .put("improvement_percent", JSONObject.NULL)
                        .put("normalized_score", JSONObject.NULL)
                        .put("classification", passed ? "compatible" : "incompatible")
                        .put("verdict", verdict);
                if (!passed) gateReasons.put("render_correctness_gate_failed:" + stepId);
                categories.add(category);
                continue;
            }

            if (gate) {
                compatibilityChecksTotal++;
                boolean gatePassed = !reportFailure;
                if (WorkloadContract.TRACE_REPLAY_ID.equals(step.optString("workload_id"))) {
                    JSONObject trace = report.optJSONObject("trace_replay");
                    gatePassed = trace != null && trace.optBoolean("passed_correctness_gate", false);
                    if (!gatePassed) gateReasons.put("trace_correctness_gate_failed:" + stepId);
                }
                if (VisualSceneContract.isVisualScene(step.optString("workload_id"))) {
                    JSONObject visual = report.optJSONObject("visual_scene");
                    gatePassed = visual != null && visual.optBoolean("passed_correctness_gate", false);
                    if (!gatePassed) gateReasons.put("visual_scene_gate_failed:" + stepId);
                    category.put("visual_checkpoint_gate_passed", gatePassed)
                            .put("minimum_pixel_match_percent", visual == null
                                    ? JSONObject.NULL : visual.opt("minimum_pixel_match_percent"))
                            .put("checkpoint_mismatch_count", visual == null
                                    ? JSONObject.NULL : visual.optInt("checkpoint_mismatch_count", 0));
                }
                if (gatePassed) compatibilityChecksPassed++;
            }

            JSONObject analysis = report.optJSONObject("statistical_analysis");
            double improvement = analysis == null ? Double.NaN
                    : analysis.optDouble("median_paired_improvement_percent", Double.NaN);
            int samples = analysis == null ? 0 : analysis.optInt("paired_sample_count", 0);
            String classification = analysis == null ? "unavailable"
                    : analysis.optString("classification", "inconclusive");
            boolean valid = !reportFailure && Double.isFinite(improvement)
                    && samples >= WorkloadContract.MINIMUM_PAIRED_SAMPLES;
            if (!valid) {
                category.put("status", "not_rankable")
                        .put("improvement_percent", JSONObject.NULL)
                        .put("normalized_score", JSONObject.NULL)
                        .put("classification", classification)
                        .put("paired_sample_count", samples)
                        .put("verdict", verdict);
                categories.add(category);
                continue;
            }
            addPerformanceCategory(category, improvement, classification, samples, verdict,
                    weight, categories, weightedImprovement, weightedScore, validWeight,
                    validPerformanceSteps, conclusiveSteps, candidateWins, systemWins, ties);
        }

        if (validPerformanceSteps[0] < minimumValidSteps) {
            gateReasons.put("insufficient_valid_performance_steps:" + validPerformanceSteps[0]
                    + "/" + minimumValidSteps);
        }
        double meanImprovement = validWeight[0] == 0 ? Double.NaN
                : weightedImprovement[0] / validWeight[0];
        double performanceIndex = validWeight[0] == 0 ? Double.NaN
                : weightedScore[0] / validWeight[0];
        double compatibilityIndex = compatibilityChecksTotal == 0
                ? (gateReasons.length() == 0 ? 100.0 : 0.0)
                : compatibilityChecksPassed * 100.0 / compatibilityChecksTotal;
        boolean eligible = gateReasons.length() == 0;
        String winner;
        String recommendation;
        if (!eligible) {
            winner = "none";
            recommendation = "not_recommended_incompatible_or_invalid";
        } else if (meanImprovement > Phase7Contract.PRACTICAL_WIN_MARGIN_PERCENT) {
            winner = "candidate";
            recommendation = "candidate_recommended_over_system";
        } else if (meanImprovement < -Phase7Contract.PRACTICAL_WIN_MARGIN_PERCENT) {
            winner = "system";
            recommendation = "system_recommended_over_candidate";
        } else {
            winner = "technical_tie";
            recommendation = "technical_tie_no_clear_winner";
        }

        categories.sort(Comparator.comparingDouble(
                item -> -item.optDouble("improvement_percent", Double.NEGATIVE_INFINITY)));
        JSONArray encoded = new JSONArray();
        for (JSONObject category : categories) encoded.put(category);
        JSONObject best = firstFinite(categories, true);
        JSONObject worst = firstFinite(categories, false);
        int highThreshold = profileVersion >= 3 ? 10 : 8;
        int highConclusive = profileVersion >= 3 ? 8 : 6;
        String confidence = validPerformanceSteps[0] >= highThreshold
                && conclusiveSteps[0] >= highConclusive && warnings.length() == 0 ? "high"
                : validPerformanceSteps[0] >= 6 && conclusiveSteps[0] >= 4 ? "medium" : "low";
        int scoreVersion = profileVersion >= 3 ? Phase11Contract.SCORE_VERSION
                : profileVersion >= 2 ? Phase8Contract.CURRENT_QUALIFICATION_SCORE_VERSION
                : Phase7Contract.SCORE_VERSION;
        String limitation = profileVersion >= 3 ? Phase11Contract.LIMITATION
                : profileVersion >= 2 ? Phase8Contract.LIMITATION : Phase7Contract.LIMITATION;

        return new JSONObject()
                .put("qualification_score_version", scoreVersion)
                .put("eligible_for_recommendation", eligible)
                .put("compatibility_gate_passed", gateReasons.length() == 0)
                .put("compatibility_index", compatibilityIndex)
                .put("compatibility_score", profileVersion >= 3
                        ? compatibilityIndex : gateReasons.length() == 0 ? 100 : 0)
                .put("compatibility_checks_total", compatibilityChecksTotal)
                .put("compatibility_checks_passed", compatibilityChecksPassed)
                .put("performance_index", finiteOrNull(performanceIndex))
                .put("overall_index", finiteOrNull(performanceIndex))
                .put("performance_weighted_improvement_percent", finiteOrNull(meanImprovement))
                .put("weighted_improvement_percent", finiteOrNull(meanImprovement))
                .put("winner", winner)
                .put("recommendation", recommendation)
                .put("confidence", confidence)
                .put("valid_performance_steps", validPerformanceSteps[0])
                .put("candidate_wins", candidateWins[0])
                .put("system_wins", systemWins[0])
                .put("technical_ties", ties[0])
                .put("gate_reasons", gateReasons)
                .put("warnings", warnings)
                .put("best_category", best == null ? JSONObject.NULL : best)
                .put("worst_category", worst == null ? JSONObject.NULL : worst)
                .put("categories", encoded)
                .put("profile_version", profileVersion)
                .put("minimum_valid_performance_steps", minimumValidSteps)
                .put("limitations", limitation);
    }

    private static int[] evaluateDeepDiagnostics(JSONObject step, JSONObject completed,
                                                  List<JSONObject> categories,
                                                  JSONArray gates, JSONArray warnings,
                                                  double[] weightedImprovement,
                                                  double[] weightedScore, int[] validWeight,
                                                  int[] validSteps, int[] conclusive,
                                                  int[] candidateWins, int[] systemWins,
                                                  int[] ties) throws Exception {
        int totalChecks = 5;
        int passedChecks = 0;
        if (completed == null || !"completed".equals(completed.optString("status"))
                || completed.optJSONObject("report") == null) {
            gates.put("missing_or_failed_step:deep_diagnostics");
            categories.add(unavailable("deep_shader_pipeline",
                    "Shader corpus e pipeline cache", 6, "deep_diagnostics_missing"));
            categories.add(unavailable("deep_synchronization",
                    "Sincronização Vulkan", 4, "deep_diagnostics_missing"));
            return new int[]{totalChecks, passedChecks};
        }
        JSONObject report = completed.getJSONObject("report");
        JSONObject comparison = report.optJSONObject("comparison");
        if (comparison == null) {
            gates.put("deep_diagnostics_comparison_missing");
            categories.add(unavailable("deep_shader_pipeline",
                    "Shader corpus e pipeline cache", 6, "comparison_missing"));
            categories.add(unavailable("deep_synchronization",
                    "Sincronização Vulkan", 4, "comparison_missing"));
            return new int[]{totalChecks, passedChecks};
        }
        JSONArray blockers = comparison.optJSONArray("blockers");
        if (blockers != null) {
            for (int i = 0; i < blockers.length(); i++) {
                String reason = blockers.optString(i, "");
                if (!reason.isEmpty()) gates.put("deep_diagnostics:" + reason);
            }
        }
        JSONArray deepWarnings = comparison.optJSONArray("warnings");
        if (deepWarnings != null) {
            for (int i = 0; i < deepWarnings.length(); i++) {
                String warning = deepWarnings.optString(i, "");
                if (!warning.isEmpty()) {
                    warnings.put("deep_diagnostics:" + warning);
                    if ("candidate_did_not_complete_memory_safe_target".equals(warning)) {
                        gates.put("deep_diagnostics:memory_safe_target_failed");
                    }
                }
            }
        }
        JSONObject format = comparison.optJSONObject("format_matrix");
        if (format != null && format.optInt("regression_count", 0) == 0) passedChecks++;
        JSONObject shader = comparison.optJSONObject("shader_pipeline_corpus");
        if (shader != null && shader.optInt("candidate_successful_cases", 0)
                >= shader.optInt("system_successful_cases", 0)) passedChecks++;
        JSONObject memory = comparison.optJSONObject("memory_pressure");
        boolean memoryPass = memory != null && !contains(deepWarnings,
                "candidate_did_not_complete_memory_safe_target");
        if (memoryPass) passedChecks++;
        JSONObject sync = comparison.optJSONObject("synchronization");
        if (sync != null && sync.optBoolean("candidate_passed", false)) passedChecks++;
        JSONObject reliability = comparison.optJSONObject("reliability_probe");
        if (reliability != null && !contains(blockers,
                "candidate_reliability_probe_failed")) passedChecks++;

        double cold = shader == null ? Double.NaN
                : shader.optDouble("cold_pipeline_change_percent", Double.NaN);
        double warm = shader == null ? Double.NaN
                : shader.optDouble("warm_pipeline_change_percent", Double.NaN);
        double pipelineImprovement = meanFinite(cold, warm);
        addDescriptive("deep_shader_pipeline", "Shader corpus e pipeline cache", 6,
                pipelineImprovement, categories, weightedImprovement, weightedScore,
                validWeight, validSteps, conclusive, candidateWins, systemWins, ties);
        double syncImprovement = sync == null ? Double.NaN
                : sync.optDouble("candidate_vs_system_fence_p99_percent", Double.NaN);
        addDescriptive("deep_synchronization", "Sincronização Vulkan", 4,
                syncImprovement, categories, weightedImprovement, weightedScore,
                validWeight, validSteps, conclusive, candidateWins, systemWins, ties);
        return new int[]{totalChecks, passedChecks};
    }

    private static int[] evaluateShortSoak(JSONObject step, JSONObject completed,
                                           List<JSONObject> categories, JSONArray gates,
                                           JSONArray warnings) throws Exception {
        JSONObject category = new JSONObject()
                .put("step_id", step.optString("step_id"))
                .put("label", step.optString("label"))
                .put("weight", 0)
                .put("evidence_type", "short_soak_gate");
        if (completed == null || !"completed".equals(completed.optString("status"))
                || completed.optJSONObject("report") == null) {
            gates.put("missing_or_failed_step:short_soak");
            category.put("status", "unavailable").put("classification", "unavailable")
                    .put("improvement_percent", JSONObject.NULL)
                    .put("normalized_score", JSONObject.NULL);
            categories.add(category);
            return new int[]{1, 0};
        }
        JSONObject comparison = completed.getJSONObject("report").optJSONObject("comparison");
        JSONArray blockers = comparison == null ? null : comparison.optJSONArray("blockers");
        JSONArray soakWarnings = comparison == null ? null : comparison.optJSONArray("warnings");
        copyValues(soakWarnings, "short_soak:", warnings);
        boolean passed = comparison != null && blockers != null && blockers.length() == 0
                && comparison.optBoolean("comparable", false);
        if (!passed) {
            if (blockers == null || blockers.length() == 0) gates.put("short_soak_invalid");
            else copyValues(blockers, "short_soak:", gates);
        }
        JSONObject soak = comparison == null ? null : comparison.optJSONObject("soak");
        double improvement = soak == null ? Double.NaN
                : soak.optDouble("candidate_vs_system_p99_percent", Double.NaN);
        category.put("status", passed ? "passed" : "failed")
                .put("classification", passed ? direction(improvement) : "incompatible")
                .put("improvement_percent", finiteOrNull(improvement))
                .put("normalized_score", JSONObject.NULL)
                .put("candidate_completed_cycles", soak == null ? JSONObject.NULL
                        : soak.opt("candidate_completed_cycles"));
        categories.add(category);
        return new int[]{1, passed ? 1 : 0};
    }

    private static void addDescriptive(String id, String label, int weight, double improvement,
                                       List<JSONObject> categories,
                                       double[] weightedImprovement, double[] weightedScore,
                                       int[] validWeight, int[] validSteps, int[] conclusive,
                                       int[] candidateWins, int[] systemWins, int[] ties)
            throws Exception {
        if (!Double.isFinite(improvement)) {
            categories.add(unavailable(id, label, weight, "descriptive_metric_unavailable"));
            return;
        }
        String classification = direction(improvement);
        JSONObject category = new JSONObject()
                .put("step_id", id).put("label", label).put("weight", weight)
                .put("evidence_type", "descriptive_deep_diagnostic")
                .put("statistical_significance_claimed", false)
                .put("status", "rankable_descriptive")
                .put("improvement_percent", improvement)
                .put("normalized_score", clamp(50.0 + improvement * 2.5, 0.0, 100.0))
                .put("classification", classification);
        categories.add(category);
        accumulate(improvement, classification, weight, weightedImprovement, weightedScore,
                validWeight, validSteps, conclusive, candidateWins, systemWins, ties);
    }

    private static void addPerformanceCategory(JSONObject category, double improvement,
                                               String classification, int samples,
                                               String verdict, int weight,
                                               List<JSONObject> categories,
                                               double[] weightedImprovement,
                                               double[] weightedScore, int[] validWeight,
                                               int[] validSteps, int[] conclusive,
                                               int[] candidateWins, int[] systemWins,
                                               int[] ties) throws Exception {
        double normalized = clamp(50.0 + improvement * 2.5, 0.0, 100.0);
        category.put("status", "rankable")
                .put("improvement_percent", improvement)
                .put("normalized_score", normalized)
                .put("classification", classification)
                .put("paired_sample_count", samples)
                .put("verdict", verdict);
        categories.add(category);
        accumulate(improvement, classification, weight, weightedImprovement, weightedScore,
                validWeight, validSteps, conclusive, candidateWins, systemWins, ties);
    }

    private static void accumulate(double improvement, String classification, int weight,
                                   double[] weightedImprovement, double[] weightedScore,
                                   int[] validWeight, int[] validSteps, int[] conclusive,
                                   int[] candidateWins, int[] systemWins, int[] ties) {
        if (weight > 0) {
            weightedImprovement[0] += improvement * weight;
            weightedScore[0] += clamp(50.0 + improvement * 2.5, 0.0, 100.0) * weight;
            validWeight[0] += weight;
            validSteps[0]++;
        }
        if ("candidate_better".equals(classification)) {
            candidateWins[0]++; conclusive[0]++;
        } else if ("candidate_worse".equals(classification)) {
            systemWins[0]++; conclusive[0]++;
        } else if ("practically_equivalent".equals(classification)) {
            ties[0]++; conclusive[0]++;
        }
    }

    private static JSONObject unavailable(String id, String label, int weight, String reason)
            throws Exception {
        return new JSONObject().put("step_id", id).put("label", label).put("weight", weight)
                .put("status", "not_rankable").put("classification", "unavailable")
                .put("improvement_percent", JSONObject.NULL)
                .put("normalized_score", JSONObject.NULL).put("reason", reason);
    }

    private static String direction(double improvement) {
        if (!Double.isFinite(improvement)) return "unavailable";
        if (improvement > Phase7Contract.PRACTICAL_WIN_MARGIN_PERCENT) return "candidate_better";
        if (improvement < -Phase7Contract.PRACTICAL_WIN_MARGIN_PERCENT) return "candidate_worse";
        return "practically_equivalent";
    }

    private static double meanFinite(double... values) {
        double sum = 0.0; int count = 0;
        for (double value : values) if (Double.isFinite(value)) { sum += value; count++; }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static boolean contains(JSONArray values, String expected) {
        if (values == null) return false;
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.optString(index))) return true;
        }
        return false;
    }

    private static void copyValues(JSONArray source, String prefix, JSONArray destination) {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            String value = source.optString(index, "");
            if (!value.isEmpty()) destination.put(prefix + value);
        }
    }

    private static JSONObject firstFinite(List<JSONObject> input, boolean best) {
        JSONObject selected = null;
        double value = best ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (JSONObject item : input) {
            double current = item.optDouble("improvement_percent", Double.NaN);
            if (!Double.isFinite(current)) continue;
            if ((best && current > value) || (!best && current < value)) {
                selected = item; value = current;
            }
        }
        return selected;
    }

    private static void copyArray(JSONObject source, String key, JSONArray destination) {
        if (source == null) return;
        JSONArray values = source.optJSONArray(key);
        if (values == null) return;
        for (int index = 0; index < values.length(); ++index) {
            String value = values.optString(index, "");
            if (!value.isEmpty()) destination.put(value);
        }
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
