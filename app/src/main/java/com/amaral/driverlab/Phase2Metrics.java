package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Phase2Metrics {
    private Phase2Metrics() {}

    static JSONObject summarize(JSONArray phases, String workloadId) throws Exception {
        String metric = WorkloadContract.primaryMetricFor(workloadId);
        List<Double> system = new ArrayList<>();
        List<Double> candidate = new ArrayList<>();
        int failed = 0;
        boolean timestampFallback = false;

        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) {
                failed++;
                continue;
            }
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult == null || !nativeResult.optBoolean("success", false)) {
                failed++;
                continue;
            }
            double value = nativeResult.optDouble(metric, Double.NaN);
            if (!Double.isFinite(value)) {
                failed++;
                continue;
            }
            if (!nativeResult.optBoolean("gpu_timestamps_used", true)) timestampFallback = true;
            if ("custom".equals(phase.optString("driver_mode"))) candidate.add(value);
            else system.add(value);
        }

        JSONObject summary = new JSONObject();
        summary.put("primary_metric", metric);
        summary.put("lower_is_better", WorkloadContract.lowerIsBetter(workloadId));
        summary.put("system", statistics(system, metric));
        summary.put("candidate", statistics(candidate, metric));
        summary.put("failed_phases", failed);
        summary.put("timestamp_fallback_observed", timestampFallback);
        summary.put("metric_note", WorkloadContract.limitationFor(workloadId));

        if (!system.isEmpty() && !candidate.isEmpty()) {
            double systemMedian = median(system);
            double candidateMedian = median(candidate);
            double rawDelta = systemMedian == 0.0
                    ? Double.NaN : (candidateMedian / systemMedian - 1.0) * 100.0;
            double improvement;
            if (WorkloadContract.lowerIsBetter(workloadId)) {
                improvement = candidateMedian == 0.0
                        ? Double.NaN : (systemMedian / candidateMedian - 1.0) * 100.0;
            } else {
                improvement = rawDelta;
            }
            summary.put("candidate_vs_system_percent",
                    Double.isFinite(rawDelta) ? rawDelta : JSONObject.NULL);
            summary.put("candidate_improvement_percent",
                    Double.isFinite(improvement) ? improvement : JSONObject.NULL);
        } else {
            summary.put("candidate_vs_system_percent", JSONObject.NULL);
            summary.put("candidate_improvement_percent", JSONObject.NULL);
        }
        return summary;
    }

    static JSONObject statistics(List<Double> input, String metric) throws Exception {
        JSONObject output = new JSONObject();
        output.put("sample_count", input.size());
        output.put("metric", metric);
        if (input.isEmpty()) {
            output.put("median", JSONObject.NULL);
            output.put("p50", JSONObject.NULL);
            output.put("p95", JSONObject.NULL);
            output.put("p99", JSONObject.NULL);
            output.put("mean", JSONObject.NULL);
            output.put("coefficient_of_variation_percent", JSONObject.NULL);
            return output;
        }
        List<Double> values = sortedFinite(input);
        double sum = 0.0;
        for (double value : values) sum += value;
        double mean = sum / values.size();
        double variance = 0.0;
        for (double value : values) variance += (value - mean) * (value - mean);
        variance /= values.size();
        output.put("median", percentileSorted(values, 0.50));
        output.put("p50", percentileSorted(values, 0.50));
        output.put("p95", percentileSorted(values, 0.95));
        output.put("p99", percentileSorted(values, 0.99));
        output.put("mean", mean);
        output.put("coefficient_of_variation_percent",
                mean == 0.0 ? JSONObject.NULL : Math.sqrt(variance) / Math.abs(mean) * 100.0);
        return output;
    }

    static double median(List<Double> values) {
        return percentile(values, 0.50);
    }

    static double percentile(List<Double> input, double quantile) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("Amostra vazia");
        if (!(quantile >= 0.0 && quantile <= 1.0)) {
            throw new IllegalArgumentException("Quantil fora do intervalo [0,1]");
        }
        return percentileSorted(sortedFinite(input), quantile);
    }

    static double onePercentLowFps(List<Double> frameTimesMs) {
        double p99 = percentile(frameTimesMs, 0.99);
        return p99 <= 0.0 ? Double.NaN : 1000.0 / p99;
    }

    static double thermalRetentionPercent(List<Double> windowThroughputs) {
        List<Double> values = sortedInputOrderFinite(windowThroughputs);
        if (values.size() < 2 || values.get(0) == 0.0) return Double.NaN;
        int edge = Math.max(1, values.size() / 5);
        double first = mean(values.subList(0, edge));
        double last = mean(values.subList(values.size() - edge, values.size()));
        return first == 0.0 ? Double.NaN : last / first * 100.0;
    }

    private static double percentileSorted(List<Double> sorted, double quantile) {
        if (sorted.size() == 1) return sorted.get(0);
        double position = quantile * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) * (1.0 - fraction) + sorted.get(upper) * fraction;
    }

    private static List<Double> sortedFinite(List<Double> input) {
        List<Double> values = sortedInputOrderFinite(input);
        Collections.sort(values);
        return values;
    }

    private static List<Double> sortedInputOrderFinite(List<Double> input) {
        List<Double> values = new ArrayList<>();
        for (Double value : input) {
            if (value != null && Double.isFinite(value)) values.add(value);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Amostra sem valores finitos");
        return values;
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.size();
    }
}
