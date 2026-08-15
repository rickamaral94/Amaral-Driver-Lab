package com.amaral.driverlab;

import org.json.JSONObject;

import java.util.Objects;

final class CampaignWorkload {
    final String workloadId;
    final int workloadVersion;
    final String traceId;

    CampaignWorkload(String workloadId, String traceId) {
        this(workloadId, WorkloadContract.versionFor(workloadId), traceId);
    }

    CampaignWorkload(String workloadId, int workloadVersion, String traceId) {
        if (!WorkloadContract.isSupported(workloadId)) {
            throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
        }
        if (!WorkloadContract.isSupportedVersion(workloadId, workloadVersion)) {
            throw new IllegalArgumentException("Versão de workload incompatível: "
                    + workloadId + "/v" + workloadVersion);
        }
        this.workloadId = workloadId;
        this.workloadVersion = workloadVersion;
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
        return workloadId + "|v" + workloadVersion
                + (traceId.isEmpty() ? "" : "|" + traceId);
    }

    String label() {
        if (traceId.isEmpty()) return WorkloadContract.labelFor(workloadId, workloadVersion);
        return WorkloadContract.labelFor(workloadId, workloadVersion) + " · "
                + TraceReplayContract.labelFor(traceId, workloadVersion);
    }

    JSONObject toJson() throws Exception {
        JSONObject output = new JSONObject()
                .put("workload_id", workloadId)
                .put("workload_version", workloadVersion)
                .put("label", label());
        output.put("trace_id", traceId.isEmpty() ? JSONObject.NULL : traceId);
        if (!traceId.isEmpty()) {
            output.put("trace_version", TraceReplayContract.definition(traceId, workloadVersion)
                    .optInt("trace_version", 1));
            output.put("trace_definition_sha256", TraceReplayContract.definition(
                    traceId, workloadVersion)
                    .optString("definition_sha256", ""));
        }
        return output;
    }

    static CampaignWorkload fromJson(JSONObject input) {
        String workloadId = input.optString("workload_id", "");
        int workloadVersion = input.optInt("workload_version", 1);
        String traceId = input.isNull("trace_id") ? "" : input.optString("trace_id", "");
        return new CampaignWorkload(workloadId, workloadVersion, traceId);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CampaignWorkload)) return false;
        CampaignWorkload value = (CampaignWorkload) other;
        return workloadId.equals(value.workloadId)
                && workloadVersion == value.workloadVersion && traceId.equals(value.traceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workloadId, workloadVersion, traceId);
    }
}
