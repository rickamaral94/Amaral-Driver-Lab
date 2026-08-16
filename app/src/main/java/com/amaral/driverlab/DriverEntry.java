package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One rendered ranking entry; compatibility is a gate, never a score component. */
final class DriverEntry {
    enum State { RANKED, DISQUALIFIED, INELIGIBLE, REFERENCE }

    final State state;
    final String binarySha256;
    final String displayName;
    final String apiVersion;
    final Integer score;
    final Integer vsStock;
    final double sustained;
    final double low;
    final double ciLow;
    final double ciHigh;
    final int n;
    final long measuredAtMs;
    final String anchorConfidence;
    final JSONArray reasons;
    final JSONArray history;
    final List<Double> sessionScores = new ArrayList<>();
    int rank;

    DriverEntry(State state, String binarySha256, String displayName, String apiVersion,
                Integer score, Integer vsStock, double sustained, double low,
                double ciLow, double ciHigh, int n, long measuredAtMs,
                String anchorConfidence, JSONArray reasons, JSONArray history) {
        this.state = state;
        this.binarySha256 = binarySha256;
        this.displayName = displayName;
        this.apiVersion = apiVersion;
        this.score = score;
        this.vsStock = vsStock;
        this.sustained = sustained;
        this.low = low;
        this.ciLow = ciLow;
        this.ciHigh = ciHigh;
        this.n = n;
        this.measuredAtMs = measuredAtMs;
        this.anchorConfidence = anchorConfidence;
        this.reasons = reasons == null ? new JSONArray() : reasons;
        this.history = history == null ? new JSONArray() : history;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("state", state.name().toLowerCase())
                .put("rank", rank <= 0 ? JSONObject.NULL : rank)
                .put("binary_sha256", binarySha256)
                .put("driver", displayName)
                .put("api_version", apiVersion == null || apiVersion.isEmpty()
                        ? JSONObject.NULL : apiVersion)
                .put("score", score == null ? JSONObject.NULL : score)
                .put("vs_stock", vsStock == null ? JSONObject.NULL : vsStock)
                .put("sustained_ratio", finite(sustained))
                .put("p1_low_ratio", finite(low))
                .put("ci95", Double.isFinite(ciLow) && Double.isFinite(ciHigh)
                        ? new JSONObject().put("low", ciLow).put("high", ciHigh)
                        : JSONObject.NULL)
                .put("n", n)
                .put("measured_at_ms", measuredAtMs)
                .put("anchor_confidence", anchorConfidence)
                .put("reasons", reasons)
                .put("history", history);
    }

    private static Object finite(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }
}
