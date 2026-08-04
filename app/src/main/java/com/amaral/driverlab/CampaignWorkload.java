package com.amaral.driverlab;

import org.json.JSONObject;

import java.util.Objects;

final class CampaignWorkload {
    final String workloadId;
    final String traceId;

    CampaignWorkload(String workloadId, String traceId) {
        if (!WorkloadContract.isSupported(workloadId)) {
            throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
        }
        this.workloadId = workloadId;
        if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
            if (!TraceReplayContract.isSupported(traceId)) {
                throw new IllegalArgumentException("Trace desconhecido: " + traceId);
            }
            this.traceId = traceId;
        } else {
            this.traceId = "";
        }
    }

    String key() {
        return workloadId + (traceId.isEmpty() ? "" : "|" + traceId);
    }

    String label() {
        if (traceId.isEmpty()) return WorkloadContract.labelFor(workloadId);
        return WorkloadContract.labelFor(workloadId) + " · " + TraceReplayContract.labelFor(traceId);
    }

    JSONObject toJson() throws Exception {
        JSONObject output = new JSONObject()
                .put("workload_id", workloadId)
                .put("workload_version", WorkloadContract.versionFor(workloadId))
                .put("label", label());
        output.put("trace_id", traceId.isEmpty() ? JSONObject.NULL : traceId);
        if (!traceId.isEmpty()) {
            output.put("trace_version", TraceReplayContract.definition(traceId)
                    .optInt("trace_version", 1));
            output.put("trace_definition_sha256", TraceReplayContract.definition(traceId)
                    .optString("definition_sha256", ""));
        }
        return output;
    }

    static CampaignWorkload fromJson(JSONObject input) {
        String workloadId = input.optString("workload_id", "");
        String traceId = input.isNull("trace_id") ? "" : input.optString("trace_id", "");
        return new CampaignWorkload(workloadId, traceId);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CampaignWorkload)) return false;
        CampaignWorkload value = (CampaignWorkload) other;
        return workloadId.equals(value.workloadId) && traceId.equals(value.traceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workloadId, traceId);
    }
}
