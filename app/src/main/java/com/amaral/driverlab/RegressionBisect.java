package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RegressionBisect {
    private enum State { GOOD, BAD, UNKNOWN }

    private RegressionBisect() {}

    static JSONObject analyze(List<SuiteRecord> input) throws Exception {
        JSONObject output = new JSONObject();
        output.put("bisect_version", Phase4Contract.BISECT_VERSION);
        output.put("limitations", Phase4Contract.LIMITATION);
        if (input.isEmpty()) return unavailable(output, "empty_sequence");
        List<SuiteRecord> records = new ArrayList<>(input);
        records.sort(Comparator.comparingLong(item -> item.finishedAtMs));
        String comparisonKey = records.get(0).comparisonKey();
        for (SuiteRecord record : records) {
            if (!comparisonKey.equals(record.comparisonKey())) {
                return unavailable(output, "mixed_hardware_workload_or_config");
            }
        }
        output.put("comparison_key", comparisonKey);
        JSONArray sequence = new JSONArray();
        int lastGood = -1;
        int firstBad = -1;
        boolean nonMonotonic = false;
        for (int index = 0; index < records.size(); ++index) {
            SuiteRecord record = records.get(index);
            State state = state(record);
            sequence.put(new JSONObject()
                    .put("index", index)
                    .put("suite_id", record.suiteId)
                    .put("candidate_sha256", record.candidateSha256)
                    .put("candidate_label", record.candidateLabel)
                    .put("finished_at_ms", record.finishedAtMs)
                    .put("state", state.name().toLowerCase())
                    .put("ranking_score_percent", Double.isFinite(record.rankingScorePercent)
                            ? record.rankingScorePercent : JSONObject.NULL));
            if (state == State.GOOD && firstBad < 0) lastGood = index;
            if (state == State.BAD && firstBad < 0 && lastGood >= 0) firstBad = index;
            if (state == State.GOOD && firstBad >= 0) nonMonotonic = true;
        }
        output.put("sequence", sequence);
        output.put("non_monotonic_observed", nonMonotonic);
        if (lastGood < 0) return unavailable(output, "no_known_good");
        if (firstBad < 0) return unavailable(output, "no_known_bad_after_good");
        output.put("available", true);
        output.put("last_known_good_index", lastGood);
        output.put("first_known_bad_index", firstBad);
        output.put("last_known_good", records.get(lastGood).compactJson());
        output.put("first_known_bad", records.get(firstBad).compactJson());
        int gap = firstBad - lastGood;
        if (gap > 1) {
            int midpoint = lastGood + gap / 2;
            output.put("status", "probe_required");
            output.put("next_probe_index", midpoint);
            output.put("next_probe", records.get(midpoint).compactJson());
        } else {
            output.put("status", nonMonotonic ? "boundary_with_non_monotonic_results"
                    : "regression_boundary_identified");
            output.put("next_probe_index", JSONObject.NULL);
            output.put("next_probe", JSONObject.NULL);
        }
        return output;
    }

    private static State state(SuiteRecord record) {
        if (record.blockingValidity || !Double.isFinite(record.rankingScorePercent)) {
            return State.UNKNOWN;
        }
        if ("candidate_worse".equals(record.classification)
                || "candidate_worse_with_confidence".equals(record.verdict)) return State.BAD;
        if ("candidate_better".equals(record.classification)
                || "practically_equivalent".equals(record.classification)
                || "candidate_better_with_confidence".equals(record.verdict)
                || "practically_equivalent_with_confidence".equals(record.verdict)) {
            return State.GOOD;
        }
        return State.UNKNOWN;
    }

    private static JSONObject unavailable(JSONObject output, String reason) throws Exception {
        output.put("available", false);
        output.put("status", "inconclusive");
        output.put("reason", reason);
        output.put("last_known_good_index", JSONObject.NULL);
        output.put("first_known_bad_index", JSONObject.NULL);
        output.put("next_probe_index", JSONObject.NULL);
        return output;
    }
}
