package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class FullTelemetryAttachment {
    private FullTelemetryAttachment() {}

    static JSONObject inspect(File filesDir, String candidateSha256) throws Exception {
        List<TelemetrySessionRecord> sessions = TelemetryStore.scan(filesDir);
        List<TelemetrySessionRecord> system = new ArrayList<>();
        List<TelemetrySessionRecord> candidate = new ArrayList<>();
        for (TelemetrySessionRecord session : sessions) {
            if ("system".equals(session.driverMode)) system.add(session);
            if ("custom".equals(session.driverMode)
                    && candidateSha256.equalsIgnoreCase(session.driverSha256)) {
                candidate.add(session);
            }
        }
        JSONArray pairs = new JSONArray();
        for (TelemetrySessionRecord left : system) {
            for (TelemetrySessionRecord right : candidate) {
                if (!left.comparisonKey().equals(right.comparisonKey())) continue;
                JSONObject comparison = TelemetryComparison.compare(left, right);
                pairs.put(new JSONObject()
                        .put("system_session", left.compactJson())
                        .put("candidate_session", right.compactJson())
                        .put("comparison", comparison));
                if (pairs.length() >= 10) break;
            }
            if (pairs.length() >= 10) break;
        }
        return new JSONObject()
                .put("telemetry_attachment_version", 1)
                .put("optional", true)
                .put("automatically_changes_score", false)
                .put("candidate_driver_sha256", candidateSha256)
                .put("system_session_count", system.size())
                .put("candidate_session_count", candidate.size())
                .put("comparable_pair_count", pairs.length())
                .put("comparable_pairs", pairs)
                .put("status", pairs.length() > 0 ? "available_not_scored" : "not_available")
                .put("limitations", Phase9Contract.LIMITATION);
    }
}
