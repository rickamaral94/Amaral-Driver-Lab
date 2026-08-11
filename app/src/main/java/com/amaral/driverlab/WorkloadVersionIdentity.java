package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

/** Reconciles the version declared by a suite/profile with versions emitted by native runtime. */
final class WorkloadVersionIdentity {
    static final String CONFIRMED = "confirmed";
    static final String MISMATCH = "mismatch";
    static final String UNDETERMINABLE = "undeterminable";

    private WorkloadVersionIdentity() {}

    static JSONObject audit(JSONObject report) throws Exception {
        int declared = report.optInt("workload_version", -1);
        JSONArray phases = report.optJSONArray("phases");
        JSONArray observations = new JSONArray();
        boolean mismatch = false;
        int observed = 0;
        int expected = phases == null ? 0 : phases.length();
        if (phases != null) {
            for (int index = 0; index < phases.length(); ++index) {
                JSONObject phase = phases.optJSONObject(index);
                JSONObject nativeResult = phase == null ? null : phase.optJSONObject("native");
                int phaseDeclared = phase == null ? -1 : phase.optInt("workload_version", -1);
                int runtime = nativeResult == null ? -1
                        : nativeResult.optInt("workload_version", -1);
                if (runtime > 0) observed++;
                if (phaseDeclared > 0 && phaseDeclared != declared
                        || runtime > 0 && runtime != declared) {
                    mismatch = true;
                }
                observations.put(new JSONObject()
                        .put("phase_index", index)
                        .put("phase_declared_version", phaseDeclared > 0
                                ? phaseDeclared : JSONObject.NULL)
                        .put("runtime_version", runtime > 0 ? runtime : JSONObject.NULL));
            }
        }
        String status = mismatch ? MISMATCH
                : declared > 0 && expected > 0 && observed == expected
                ? CONFIRMED : UNDETERMINABLE;
        return new JSONObject()
                .put("policy_version", 1)
                .put("declared_workload_version", declared > 0
                        ? declared : JSONObject.NULL)
                .put("expected_observation_count", expected)
                .put("observed_runtime_version_count", observed)
                .put("status", status)
                .put("eligible_for_aggregation", CONFIRMED.equals(status))
                .put("observations", observations);
    }

    static JSONObject aggregateQualification(JSONArray completedSteps) throws Exception {
        int confirmed = 0;
        int mismatch = 0;
        int undeterminable = 0;
        JSONArray steps = new JSONArray();
        for (int index = 0; index < completedSteps.length(); ++index) {
            JSONObject state = completedSteps.optJSONObject(index);
            JSONObject report = state == null ? null : state.optJSONObject("report");
            if (report == null || !report.has("workload_id")) continue;
            JSONObject audit = report.optJSONObject("workload_version_audit");
            if (audit == null) audit = audit(report);
            String status = audit.optString("status", UNDETERMINABLE);
            if (CONFIRMED.equals(status)) confirmed++;
            else if (MISMATCH.equals(status)) mismatch++;
            else undeterminable++;
            steps.put(new JSONObject()
                    .put("step_id", state.optString("step_id"))
                    .put("status", status));
        }
        boolean eligible = mismatch == 0 && undeterminable == 0 && confirmed > 0;
        String status = mismatch > 0 ? MISMATCH
                : eligible ? CONFIRMED : UNDETERMINABLE;
        return new JSONObject()
                .put("policy_version", 1)
                .put("confirmed_step_count", confirmed)
                .put("mismatch_step_count", mismatch)
                .put("undeterminable_step_count", undeterminable)
                .put("status", status)
                .put("eligible_for_aggregation", eligible)
                .put("steps", steps);
    }
}
