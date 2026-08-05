package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

final class SuiteDiff {
    private SuiteDiff() {}

    static JSONObject compare(SuiteRecord left, SuiteRecord right) throws Exception {
        JSONObject output = new JSONObject();
        output.put("suite_diff_version", Phase4Contract.SUITE_DIFF_VERSION);
        output.put("left", left.compactJson());
        output.put("right", right.compactJson());
        JSONArray incompatibilities = new JSONArray();
        if (!left.workloadId.equals(right.workloadId)) {
            incompatibilities.put("different_workload_id");
        }
        if (left.workloadVersion != right.workloadVersion) {
            incompatibilities.put("different_workload_version");
        }
        if (!left.hardwareKey.equals(right.hardwareKey)) {
            incompatibilities.put("different_hardware_key");
        }
        if (!sameConfig(left.report, right.report)) {
            incompatibilities.put("different_workload_config");
        }
        if (analysisVersion(left.report) != analysisVersion(right.report)) {
            incompatibilities.put("different_analysis_version");
        }
        boolean comparable = incompatibilities.length() == 0;
        output.put("historically_comparable", comparable);
        output.put("incompatibilities", incompatibilities);
        output.put("limitations", Phase4Contract.LIMITATION);

        JSONObject metricDiff = new JSONObject();
        metricDiff.put("metric", left.primaryMetric);
        metricDiff.put("left_ranking_score_percent", finiteOrNull(left.rankingScorePercent));
        metricDiff.put("right_ranking_score_percent", finiteOrNull(right.rankingScorePercent));
        if (comparable && Double.isFinite(left.rankingScorePercent)
                && Double.isFinite(right.rankingScorePercent)) {
            metricDiff.put("right_minus_left_percentage_points",
                    right.rankingScorePercent - left.rankingScorePercent);
        } else {
            metricDiff.put("right_minus_left_percentage_points", JSONObject.NULL);
        }
        output.put("metric_diff", metricDiff);

        output.put("verdict_changed", !left.verdict.equals(right.verdict));
        output.put("classification_changed", !left.classification.equals(right.classification));
        output.put("candidate_changed", !left.candidateSha256.equals(right.candidateSha256));
        output.put("warnings_added", difference(right.warnings, left.warnings));
        output.put("warnings_removed", difference(left.warnings, right.warnings));
        output.put("blocking_validity_changed",
                left.blockingValidity != right.blockingValidity);
        output.put("capability_summary", capabilitySummary(left.report, right.report));
        return output;
    }

    private static JSONObject capabilitySummary(JSONObject left, JSONObject right) throws Exception {
        JSONObject leftDiff = left.optJSONObject("capability_diff");
        JSONObject rightDiff = right.optJSONObject("capability_diff");
        return new JSONObject()
                .put("left_extensions_gained", arrayLength(leftDiff, "extensions_gained"))
                .put("left_extensions_lost", arrayLength(leftDiff, "extensions_lost"))
                .put("right_extensions_gained", arrayLength(rightDiff, "extensions_gained"))
                .put("right_extensions_lost", arrayLength(rightDiff, "extensions_lost"));
    }

    private static int arrayLength(JSONObject object, String key) {
        JSONArray array = object == null ? null : object.optJSONArray(key);
        return array == null ? 0 : array.length();
    }

    private static JSONArray difference(Iterable<String> left, Iterable<String> right) {
        Set<String> remaining = new LinkedHashSet<>();
        for (String value : left) remaining.add(value);
        for (String value : right) remaining.remove(value);
        JSONArray output = new JSONArray();
        for (String value : remaining) output.put(value);
        return output;
    }

    private static boolean sameConfig(JSONObject left, JSONObject right) {
        try {
            return JsonCanonicalizer.canonicalize(left.optJSONObject("workload_config"))
                    .equals(JsonCanonicalizer.canonicalize(
                            right.optJSONObject("workload_config")));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int analysisVersion(JSONObject report) {
        JSONObject contract = report.optJSONObject("analysis_contract");
        return contract == null ? 0 : contract.optInt("analysis_version", 0);
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }
}
