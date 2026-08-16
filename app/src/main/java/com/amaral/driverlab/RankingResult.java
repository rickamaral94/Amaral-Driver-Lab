package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class RankingResult {
    boolean blocked;
    String blockedReason;
    double noiseFloor;
    String epoch;
    JSONObject anchorDrift;
    final List<DriverEntry> ranked = new ArrayList<>();
    final List<DriverEntry> disqualified = new ArrayList<>();
    final List<DriverEntry> ineligible = new ArrayList<>();
    final List<DriverEntry> references = new ArrayList<>();

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("ranking_schema_version", 1)
                .put("blocked", blocked)
                .put("blocked_reason", blockedReason == null ? JSONObject.NULL : blockedReason)
                .put("noise_floor", noiseFloor)
                .put("epoch_sha256", epoch)
                .put("anchor_drift", anchorDrift == null ? JSONObject.NULL : anchorDrift)
                .put("ranked", entries(ranked))
                .put("disqualified", entries(disqualified))
                .put("ineligible", entries(ineligible))
                .put("references", entries(references));
    }

    private static JSONArray entries(List<DriverEntry> source) throws Exception {
        JSONArray output = new JSONArray();
        for (DriverEntry entry : source) output.put(entry.toJson());
        return output;
    }
}
