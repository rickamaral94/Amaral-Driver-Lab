package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TelemetrySummary {
    private TelemetrySummary() {}

    static JSONObject summarize(JSONObject session) throws Exception {
        JSONArray samples = session.optJSONArray("samples");
        JSONArray events = session.optJSONArray("events");
        List<Double> frameTimes = new ArrayList<>();
        List<Double> gpuTimes = new ArrayList<>();
        List<Double> presentWaits = new ArrayList<>();
        int over25 = 0;
        int over50 = 0;
        double total = 0.0;
        double worst = Double.NaN;
        if (samples != null) {
            for (int index = 0; index < samples.length(); ++index) {
                JSONObject sample = samples.getJSONObject(index);
                double frame = sample.getDouble("frame_time_ms");
                frameTimes.add(frame);
                total += frame;
                if (!Double.isFinite(worst) || frame > worst) worst = frame;
                if (frame > 25.0) over25++;
                if (frame > 50.0) over50++;
                double gpu = sample.optDouble("gpu_time_ms", Double.NaN);
                if (Double.isFinite(gpu)) gpuTimes.add(gpu);
                double present = sample.optDouble("present_wait_ms", Double.NaN);
                if (Double.isFinite(present)) presentWaits.add(present);
            }
        }
        Collections.sort(frameTimes);
        Collections.sort(gpuTimes);
        Collections.sort(presentWaits);

        int crashCount = 0;
        int deviceLostCount = 0;
        int graphicsWarningCount = 0;
        int shaderCompileCount = 0;
        int stutterMarkerCount = 0;
        int fatalEventCount = 0;
        List<Double> temperatures = new ArrayList<>();
        List<Double> batteryPercent = new ArrayList<>();
        List<Double> powerWatts = new ArrayList<>();
        if (events != null) {
            for (int index = 0; index < events.length(); ++index) {
                JSONObject event = events.getJSONObject(index);
                String type = event.optString("type");
                if ("crash".equals(type)) crashCount++;
                else if ("device_lost".equals(type)) deviceLostCount++;
                else if ("graphics_warning".equals(type)) graphicsWarningCount++;
                else if ("shader_compile".equals(type)) shaderCompileCount++;
                else if ("stutter_marker".equals(type)) stutterMarkerCount++;
                if ("fatal".equals(event.optString("severity"))) fatalEventCount++;
                if ("thermal_sample".equals(type)) {
                    JSONObject details = event.optJSONObject("details");
                    addFinite(temperatures, details, "temperature_c");
                    addFinite(batteryPercent, details, "battery_percent");
                    addFinite(powerWatts, details, "power_w");
                }
            }
        }

        long created = session.optLong("created_at_ms", 0L);
        long finished = session.optLong("finished_at_ms", created);
        JSONObject frame = new JSONObject()
                .put("sample_count", frameTimes.size())
                .put("p50_frame_ms", nullable(percentile(frameTimes, 0.50)))
                .put("p95_frame_ms", nullable(percentile(frameTimes, 0.95)))
                .put("p99_frame_ms", nullable(percentile(frameTimes, 0.99)))
                .put("mean_frame_ms", nullable(frameTimes.isEmpty()
                        ? Double.NaN : total / frameTimes.size()))
                .put("worst_frame_ms", nullable(worst))
                .put("one_percent_low_fps", nullable(onePercentLowFps(frameTimes)))
                .put("frames_over_25_ms", over25)
                .put("frames_over_50_ms", over50)
                .put("stutter_ratio_over_25_ms", nullable(ratio(over25, frameTimes.size())))
                .put("severe_stutter_ratio_over_50_ms", nullable(ratio(over50, frameTimes.size())));

        return new JSONObject()
                .put("telemetry_summary_version", Phase9Contract.TELEMETRY_SUMMARY_VERSION)
                .put("session_id", session.optString("session_id"))
                .put("duration_ms", Math.max(0L, finished - created))
                .put("frame_metrics_available", !frameTimes.isEmpty())
                .put("frame", frame)
                .put("gpu", metricObject(gpuTimes, "gpu_time_ms"))
                .put("present_wait", metricObject(presentWaits, "present_wait_ms"))
                .put("events", new JSONObject()
                        .put("event_count", events == null ? 0 : events.length())
                        .put("crash_count", crashCount)
                        .put("device_lost_count", deviceLostCount)
                        .put("graphics_warning_count", graphicsWarningCount)
                        .put("shader_compile_count", shaderCompileCount)
                        .put("stutter_marker_count", stutterMarkerCount)
                        .put("fatal_event_count", fatalEventCount))
                .put("thermal", new JSONObject()
                        .put("sample_count", temperatures.size())
                        .put("temperature_start_c", nullable(first(temperatures)))
                        .put("temperature_end_c", nullable(last(temperatures)))
                        .put("temperature_max_c", nullable(max(temperatures)))
                        .put("battery_start_percent", nullable(first(batteryPercent)))
                        .put("battery_end_percent", nullable(last(batteryPercent)))
                        .put("power_mean_w", nullable(mean(powerWatts))))
                .put("limitations", Phase9Contract.LIMITATION);
    }

    private static JSONObject metricObject(List<Double> values, String metric) throws Exception {
        return new JSONObject()
                .put("metric", metric)
                .put("sample_count", values.size())
                .put("p50", nullable(percentile(values, 0.50)))
                .put("p95", nullable(percentile(values, 0.95)))
                .put("p99", nullable(percentile(values, 0.99)))
                .put("mean", nullable(mean(values)));
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return Double.NaN;
        if (sorted.size() == 1) return sorted.get(0);
        double position = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }

    private static double onePercentLowFps(List<Double> sorted) {
        if (sorted.isEmpty()) return Double.NaN;
        int count = Math.max(1, (int) Math.ceil(sorted.size() * 0.01));
        double total = 0.0;
        for (int index = sorted.size() - count; index < sorted.size(); ++index) {
            total += sorted.get(index);
        }
        double averageWorstFrame = total / count;
        return averageWorstFrame <= 0.0 ? Double.NaN : 1_000.0 / averageWorstFrame;
    }

    private static void addFinite(List<Double> output, JSONObject source, String key) {
        if (source == null) return;
        double value = source.optDouble(key, Double.NaN);
        if (Double.isFinite(value)) output.add(value);
    }

    private static double ratio(int count, int total) {
        return total <= 0 ? Double.NaN : (double) count / total;
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double total = 0.0;
        for (double value : values) total += value;
        return total / values.size();
    }

    private static double first(List<Double> values) {
        return values.isEmpty() ? Double.NaN : values.get(0);
    }

    private static double last(List<Double> values) {
        return values.isEmpty() ? Double.NaN : values.get(values.size() - 1);
    }

    private static double max(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double result = -Double.MAX_VALUE;
        for (double value : values) result = Math.max(result, value);
        return result;
    }

    private static Object nullable(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }
}
