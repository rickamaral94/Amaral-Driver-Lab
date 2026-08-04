package com.amaral.driverlab;

import com.amaral.driverlab.telemetry.TelemetryContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TelemetryAnalysis {
    private static final long FRAME_16_67_MS_NS = 16_666_667L;
    private static final long FRAME_33_33_MS_NS = 33_333_333L;
    private static final long FRAME_50_MS_NS = 50_000_000L;
    private static final long FRAME_100_MS_NS = 100_000_000L;

    private TelemetryAnalysis() {}

    static JSONObject analyze(JSONObject bundle) throws Exception {
        TelemetryContract.validateBundle(bundle);
        JSONObject session = bundle.getJSONObject("session");
        JSONArray events = bundle.getJSONArray("events");
        List<Long> frames = new ArrayList<>();
        long frameTotalNs = 0L;
        int markerCount = 0;
        int vulkanErrorCount = 0;
        int renderWarningCount = 0;
        int crashCount = 0;
        int hangCount = 0;
        int stateCount = 0;
        for (int index = 0; index < events.length(); ++index) {
            JSONObject event = events.getJSONObject(index);
            switch (event.getString("type")) {
                case TelemetryContract.EVENT_FRAME:
                    long frameNs = event.getLong("frame_delta_ns");
                    frames.add(frameNs);
                    frameTotalNs += frameNs;
                    break;
                case TelemetryContract.EVENT_MARKER:
                    markerCount++;
                    break;
                case TelemetryContract.EVENT_VULKAN_ERROR:
                    vulkanErrorCount++;
                    break;
                case TelemetryContract.EVENT_RENDER_WARNING:
                    renderWarningCount++;
                    break;
                case TelemetryContract.EVENT_CRASH:
                    crashCount++;
                    break;
                case TelemetryContract.EVENT_HANG:
                    hangCount++;
                    break;
                case TelemetryContract.EVENT_SESSION_STATE:
                    stateCount++;
                    break;
                default:
                    throw new IllegalArgumentException("evento não suportado após validação");
            }
        }

        JSONObject output = new JSONObject()
                .put("telemetry_analysis_version", Phase7Contract.TELEMETRY_ANALYSIS_VERSION)
                .put("sample_unit", "producer_reported_presented_frame_delta")
                .put("frame_metric", TelemetryContract.FRAME_METRIC_DELTA_NS)
                .put("analysis_available", !frames.isEmpty())
                .put("frame_count", frames.size())
                .put("sampled_frame_duration_ms", frameTotalNs / 1_000_000.0)
                .put("marker_count", markerCount)
                .put("vulkan_error_count", vulkanErrorCount)
                .put("render_warning_count", renderWarningCount)
                .put("crash_count", crashCount)
                .put("hang_count", hangCount)
                .put("session_state_event_count", stateCount)
                .put("terminal_status", session.getString("status"))
                .put("cross_session_score_available", false)
                .put("driver_winner", JSONObject.NULL)
                .put("sampling_coverage_known", false)
                .put("metric_limitations", Phase7Contract.LIMITATION);

        JSONArray warnings = new JSONArray();
        if (frames.isEmpty()) {
            warnings.put("no_frame_samples");
            output.put("frame_time_ms", JSONObject.NULL);
            output.put("estimated_average_fps", JSONObject.NULL);
            output.put("thresholds", thresholdJson(frames));
        } else {
            List<Long> sorted = new ArrayList<>(frames);
            Collections.sort(sorted);
            double meanNs = frameTotalNs / (double) frames.size();
            output.put("frame_time_ms", new JSONObject()
                    .put("minimum", sorted.get(0) / 1_000_000.0)
                    .put("mean", meanNs / 1_000_000.0)
                    .put("median", percentile(sorted, 50.0) / 1_000_000.0)
                    .put("p95", percentile(sorted, 95.0) / 1_000_000.0)
                    .put("p99", percentile(sorted, 99.0) / 1_000_000.0)
                    .put("maximum", sorted.get(sorted.size() - 1) / 1_000_000.0));
            output.put("estimated_average_fps", 1_000_000_000.0 / meanNs);
            output.put("thresholds", thresholdJson(frames));
            if (frames.size() < 300) warnings.put("fewer_than_300_frame_samples");
            if (frameTotalNs < 10_000_000_000L) warnings.put("sampled_frame_duration_below_10_seconds");
        }
        if (!"complete".equals(session.getString("status"))) warnings.put("session_not_complete");
        if (crashCount > 0) warnings.put("crash_event_present");
        if (hangCount > 0) warnings.put("hang_event_present");
        if (vulkanErrorCount > 0) warnings.put("vulkan_error_event_present");
        output.put("validity_warnings", warnings);
        output.put("descriptive_quality", warnings.length() == 0
                ? "descriptive_sample_complete" : "descriptive_sample_with_caveats");
        return output;
    }

    private static JSONObject thresholdJson(List<Long> frames) throws Exception {
        return new JSONObject()
                .put("over_16_67_ms", threshold(frames, FRAME_16_67_MS_NS))
                .put("over_33_33_ms", threshold(frames, FRAME_33_33_MS_NS))
                .put("over_50_ms", threshold(frames, FRAME_50_MS_NS))
                .put("over_100_ms", threshold(frames, FRAME_100_MS_NS));
    }

    private static JSONObject threshold(List<Long> frames, long thresholdNs) throws Exception {
        int count = 0;
        for (long value : frames) if (value > thresholdNs) count++;
        return new JSONObject()
                .put("count", count)
                .put("percent", frames.isEmpty() ? JSONObject.NULL : count * 100.0 / frames.size());
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) throw new IllegalArgumentException("amostra vazia");
        double position = percentile / 100.0 * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return Math.round(sorted.get(lower) * (1.0 - fraction) + sorted.get(upper) * fraction);
    }
}
