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
        double weightedImprovement = 0.0;
        double weightedScore = 0.0;
        int validWeight = 0;
        int validPerformanceSteps = 0;
        int conclusiveSteps = 0;
        int candidateWins = 0;
        int systemWins = 0;
        int ties = 0;

        JSONArray profileSteps = profile.getJSONArray("steps");
        for (int index = 0; index < profileSteps.length(); ++index) {
            JSONObject step = profileSteps.getJSONObject(index);
            String stepId = step.getString("step_id");
            int weight = step.optInt("score_weight", 0);
            boolean gate = step.optBoolean("compatibility_gate", false);
            JSONObject completed = byStep.get(stepId);
            JSONObject category = new JSONObject()
                    .put("step_id", stepId)
                    .put("label", step.optString("label"))
                    .put("workload_id", step.optString("workload_id"))
                    .put("trace_id", step.isNull("trace_id") ? JSONObject.NULL
                            : step.opt("trace_id"))
                    .put("weight", weight);

            if (completed == null || !"completed".equals(completed.optString("status"))) {
                category.put("status", completed == null ? "missing" : completed.optString("status"))
                        .put("improvement_percent", JSONObject.NULL)
                        .put("normalized_score", JSONObject.NULL)
                        .put("classification", "unavailable");
                gateReasons.put("missing_or_failed_step:" + stepId);
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
                categories.add(category);
                continue;
            }

            String verdict = report.optString("verdict", "unknown");
            boolean reportFailure = verdict.startsWith("failed_")
                    || report.optJSONArray("failure_catalog") != null
                    && report.optJSONArray("failure_catalog").length() > 0;
            if (reportFailure) gateReasons.put("blocking_suite:" + stepId + ":" + verdict);

            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(step.optString("workload_id"))) {
                boolean passed = "passed_render_correctness".equals(verdict)
                        && report.optJSONObject("render_correctness") != null
                        && report.optJSONObject("render_correctness").optBoolean("passed", false);
                category.put("status", passed ? "passed" : "failed")
                        .put("improvement_percent", JSONObject.NULL)
                        .put("normalized_score", JSONObject.NULL)
                        .put("classification", passed ? "compatible" : "incompatible")
                        .put("verdict", verdict);
                if (!passed) gateReasons.put("render_correctness_gate_failed:" + stepId);
                categories.add(category);
                continue;
            }

            if (gate && WorkloadContract.TRACE_REPLAY_ID.equals(step.optString("workload_id"))) {
                JSONObject trace = report.optJSONObject("trace_replay");
                if (trace == null || !trace.optBoolean("passed_correctness_gate", false)) {
                    gateReasons.put("trace_correctness_gate_failed:" + stepId);
                }
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

            double normalized = clamp(50.0 + improvement * 2.5, 0.0, 100.0);
            category.put("status", "rankable")
                    .put("improvement_percent", improvement)
                    .put("normalized_score", normalized)
                    .put("classification", classification)
                    .put("paired_sample_count", samples)
                    .put("verdict", verdict);
            categories.add(category);
            if (weight > 0) {
                weightedImprovement += improvement * weight;
                weightedScore += normalized * weight;
                validWeight += weight;
                validPerformanceSteps++;
            }
            if ("candidate_better".equals(classification)) {
                candidateWins++;
                conclusiveSteps++;
            } else if ("candidate_worse".equals(classification)) {
                systemWins++;
                conclusiveSteps++;
            } else if ("practically_equivalent".equals(classification)) {
                ties++;
                conclusiveSteps++;
            }
        }

        if (validPerformanceSteps < Phase7Contract.MINIMUM_VALID_PERFORMANCE_STEPS) {
            gateReasons.put("insufficient_valid_performance_steps:" + validPerformanceSteps);
        }
        double meanImprovement = validWeight == 0 ? Double.NaN
                : weightedImprovement / validWeight;
        double overallIndex = validWeight == 0 ? Double.NaN : weightedScore / validWeight;
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
        String confidence = validPerformanceSteps >= 8 && conclusiveSteps >= 6
                && warnings.length() == 0 ? "high"
                : validPerformanceSteps >= 6 && conclusiveSteps >= 4 ? "medium" : "low";

        return new JSONObject()
                .put("qualification_score_version", Phase7Contract.SCORE_VERSION)
                .put("eligible_for_recommendation", eligible)
                .put("compatibility_gate_passed", gateReasons.length() == 0)
                .put("compatibility_score", gateReasons.length() == 0 ? 100 : 0)
                .put("overall_index", Double.isFinite(overallIndex) ? overallIndex : JSONObject.NULL)
                .put("weighted_improvement_percent",
                        Double.isFinite(meanImprovement) ? meanImprovement : JSONObject.NULL)
                .put("winner", winner)
                .put("recommendation", recommendation)
                .put("confidence", confidence)
                .put("valid_performance_steps", validPerformanceSteps)
                .put("candidate_wins", candidateWins)
                .put("system_wins", systemWins)
                .put("technical_ties", ties)
                .put("gate_reasons", gateReasons)
                .put("warnings", warnings)
                .put("best_category", best == null ? JSONObject.NULL : best)
                .put("worst_category", worst == null ? JSONObject.NULL : worst)
                .put("categories", encoded)
                .put("limitations", Phase7Contract.LIMITATION);
    }

    private static JSONObject firstFinite(List<JSONObject> input, boolean best) {
        JSONObject selected = null;
        double value = best ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (JSONObject item : input) {
            double current = item.optDouble("improvement_percent", Double.NaN);
            if (!Double.isFinite(current)) continue;
            if ((best && current > value) || (!best && current < value)) {
                selected = item;
                value = current;
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

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
