package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

final class StatisticalComparison {
    private static final double EPSILON = 1e-12;

    private StatisticalComparison() {}

    private static final class Pair {
        final int round;
        Double system;
        Double candidate;
        int systemIndex = Integer.MAX_VALUE;
        int candidateIndex = Integer.MAX_VALUE;

        Pair(int round) {
            this.round = round;
        }

        boolean complete() {
            return system != null && candidate != null;
        }

        boolean candidateFirst() {
            return candidateIndex < systemIndex;
        }
    }

    static JSONObject contractJson() throws Exception {
        JSONObject contract = new JSONObject();
        contract.put("analysis_version", WorkloadContract.STATISTICAL_ANALYSIS_VERSION);
        contract.put("sample_unit", "paired_ab_round");
        contract.put("primary_estimator", "median_paired_improvement_percent");
        contract.put("confidence_level", WorkloadContract.CONFIDENCE_LEVEL);
        contract.put("bootstrap_method", "deterministic_percentile_bootstrap");
        contract.put("bootstrap_iterations", WorkloadContract.BOOTSTRAP_ITERATIONS);
        contract.put("minimum_paired_samples", WorkloadContract.MINIMUM_PAIRED_SAMPLES);
        contract.put("practical_margin_percent",
                WorkloadContract.PRACTICAL_EQUIVALENCE_MARGIN_PERCENT);
        contract.put("significance_test", "exact_two_sided_sign_test");
        contract.put("classification_policy",
                "confidence_interval_vs_practical_margin");
        contract.put("limitations", WorkloadContract.STATISTICAL_ANALYSIS_LIMITATION);
        return contract;
    }

    static JSONObject analyze(JSONArray phases, String workloadId) throws Exception {
        if (!WorkloadContract.isPerformance(workloadId)) {
            throw new IllegalArgumentException("Workload sem análise estatística: " + workloadId);
        }
        String metric = WorkloadContract.primaryMetricFor(workloadId);
        boolean lowerIsBetter = WorkloadContract.lowerIsBetter(workloadId);
        Map<Integer, Pair> pairs = new TreeMap<>();
        List<Double> systemValues = new ArrayList<>();
        List<Double> candidateValues = new ArrayList<>();
        int failedPhases = 0;
        int duplicateMeasurements = 0;

        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) {
                failedPhases++;
                continue;
            }
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult == null || !nativeResult.optBoolean("success", false)) {
                failedPhases++;
                continue;
            }
            double value = nativeResult.optDouble(metric, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0) {
                failedPhases++;
                continue;
            }
            int round = phase.optInt("round", -1);
            if (round <= 0) {
                failedPhases++;
                continue;
            }
            Pair pair = pairs.computeIfAbsent(round, Pair::new);
            boolean custom = "custom".equals(phase.optString("driver_mode"));
            if (custom) {
                candidateValues.add(value);
                if (pair.candidate != null) duplicateMeasurements++;
                else {
                    pair.candidate = value;
                    pair.candidateIndex = index;
                }
            } else {
                systemValues.add(value);
                if (pair.system != null) duplicateMeasurements++;
                else {
                    pair.system = value;
                    pair.systemIndex = index;
                }
            }
        }

        List<Double> improvements = new ArrayList<>();
        List<Double> systemFirstImprovements = new ArrayList<>();
        List<Double> candidateFirstImprovements = new ArrayList<>();
        JSONArray pairedRounds = new JSONArray();
        int incompletePairs = 0;
        for (Pair pair : pairs.values()) {
            if (!pair.complete()) {
                incompletePairs++;
                continue;
            }
            double improvement = improvementPercent(
                    pair.system, pair.candidate, lowerIsBetter);
            if (!Double.isFinite(improvement)) {
                incompletePairs++;
                continue;
            }
            improvements.add(improvement);
            if (pair.candidateFirst()) candidateFirstImprovements.add(improvement);
            else systemFirstImprovements.add(improvement);
            JSONObject item = new JSONObject();
            item.put("round", pair.round);
            item.put("system_value", pair.system);
            item.put("candidate_value", pair.candidate);
            item.put("candidate_improvement_percent", improvement);
            item.put("order", pair.candidateFirst() ? "candidate_then_system"
                    : "system_then_candidate");
            pairedRounds.put(item);
        }

        JSONObject output = new JSONObject();
        output.put("analysis_version", WorkloadContract.STATISTICAL_ANALYSIS_VERSION);
        output.put("available", !improvements.isEmpty());
        output.put("primary_metric", metric);
        output.put("lower_is_better", lowerIsBetter);
        output.put("paired_design", "round-matched AB/BA alternating");
        output.put("paired_sample_count", improvements.size());
        output.put("incomplete_pair_count", incompletePairs);
        output.put("failed_phase_count", failedPhases);
        output.put("duplicate_measurement_count", duplicateMeasurements);
        output.put("paired_rounds", pairedRounds);
        output.put("system", Phase2Metrics.statistics(systemValues, metric));
        output.put("candidate", Phase2Metrics.statistics(candidateValues, metric));
        output.put("confidence_level", WorkloadContract.CONFIDENCE_LEVEL);
        output.put("bootstrap_iterations", WorkloadContract.BOOTSTRAP_ITERATIONS);
        output.put("practical_margin_percent",
                WorkloadContract.PRACTICAL_EQUIVALENCE_MARGIN_PERCENT);
        output.put("minimum_paired_samples", WorkloadContract.MINIMUM_PAIRED_SAMPLES);
        output.put("metric_note", WorkloadContract.limitationFor(workloadId));
        output.put("analysis_limitations", WorkloadContract.STATISTICAL_ANALYSIS_LIMITATION);

        if (improvements.isEmpty()) {
            putNullInference(output);
            output.put("classification", "insufficient_data");
            return output;
        }

        double medianImprovement = Phase2Metrics.median(improvements);
        double meanImprovement = mean(improvements);
        double[] interval = bootstrapMedianInterval(improvements, workloadId);
        int wins = 0;
        int ties = 0;
        int losses = 0;
        for (double improvement : improvements) {
            if (improvement > EPSILON) wins++;
            else if (improvement < -EPSILON) losses++;
            else ties++;
        }
        output.put("median_paired_improvement_percent", medianImprovement);
        output.put("mean_paired_improvement_percent", meanImprovement);
        output.put("confidence_interval_95_percent", new JSONObject()
                .put("lower", interval[0])
                .put("upper", interval[1]));
        output.put("wins", wins);
        output.put("ties", ties);
        output.put("losses", losses);
        output.put("probability_of_superiority_percent",
                (wins + 0.5 * ties) / improvements.size() * 100.0);
        output.put("matched_rank_biserial_correlation",
                matchedRankBiserial(improvements));
        output.put("exact_sign_test_two_sided_p_value", exactSignTestPValue(wins, losses));
        output.put("classification", classify(improvements.size(), interval[0], interval[1]));

        JSONObject orderBias = new JSONObject();
        orderBias.put("system_first_sample_count", systemFirstImprovements.size());
        orderBias.put("candidate_first_sample_count", candidateFirstImprovements.size());
        if (!systemFirstImprovements.isEmpty() && !candidateFirstImprovements.isEmpty()) {
            double systemFirstMedian = Phase2Metrics.median(systemFirstImprovements);
            double candidateFirstMedian = Phase2Metrics.median(candidateFirstImprovements);
            orderBias.put("system_first_median_improvement_percent", systemFirstMedian);
            orderBias.put("candidate_first_median_improvement_percent", candidateFirstMedian);
            orderBias.put("median_difference_percent_points",
                    candidateFirstMedian - systemFirstMedian);
        } else {
            orderBias.put("system_first_median_improvement_percent", JSONObject.NULL);
            orderBias.put("candidate_first_median_improvement_percent", JSONObject.NULL);
            orderBias.put("median_difference_percent_points", JSONObject.NULL);
        }
        output.put("order_bias_diagnostic", orderBias);
        return output;
    }

    static String verdictFor(JSONObject analysis, int failedPhases) {
        if (analysis == null || !analysis.optBoolean("available", false)) {
            return failedPhases > 0 ? "failed_execution" : "insufficient_statistical_data";
        }
        String classification = analysis.optString("classification", "inconclusive");
        if (failedPhases > 0
                && analysis.optInt("paired_sample_count", 0)
                < WorkloadContract.MINIMUM_PAIRED_SAMPLES) {
            return "failed_execution";
        }
        if ("candidate_better".equals(classification)) return "candidate_better_with_confidence";
        if ("candidate_worse".equals(classification)) return "candidate_worse_with_confidence";
        if ("practically_equivalent".equals(classification)) {
            return "practically_equivalent_with_confidence";
        }
        if ("insufficient_samples".equals(classification)) return "insufficient_statistical_data";
        return "inconclusive_statistical_comparison";
    }

    static double improvementPercent(double system, double candidate, boolean lowerIsBetter) {
        if (!Double.isFinite(system) || !Double.isFinite(candidate) || system <= 0.0) {
            return Double.NaN;
        }
        return lowerIsBetter
                ? (system - candidate) / system * 100.0
                : (candidate - system) / system * 100.0;
    }

    static double exactSignTestPValue(int wins, int losses) {
        int n = wins + losses;
        if (n == 0) return 1.0;
        int k = Math.min(wins, losses);
        double cumulative = 0.0;
        for (int i = 0; i <= k; ++i) cumulative += binomialProbability(n, i);
        return Math.min(1.0, 2.0 * cumulative);
    }

    static double matchedRankBiserial(List<Double> differences) {
        List<double[]> ranked = new ArrayList<>();
        for (double value : differences) {
            if (Double.isFinite(value) && Math.abs(value) > EPSILON) {
                ranked.add(new double[]{Math.abs(value), value});
            }
        }
        if (ranked.isEmpty()) return 0.0;
        ranked.sort(Comparator.comparingDouble(value -> value[0]));
        double positive = 0.0;
        double negative = 0.0;
        int index = 0;
        while (index < ranked.size()) {
            int end = index + 1;
            while (end < ranked.size()
                    && Math.abs(ranked.get(end)[0] - ranked.get(index)[0]) <= EPSILON) end++;
            double averageRank = ((index + 1) + end) / 2.0;
            for (int cursor = index; cursor < end; ++cursor) {
                if (ranked.get(cursor)[1] > 0.0) positive += averageRank;
                else negative += averageRank;
            }
            index = end;
        }
        double total = positive + negative;
        return total == 0.0 ? 0.0 : (positive - negative) / total;
    }

    private static String classify(int sampleCount, double lower, double upper) {
        if (sampleCount < WorkloadContract.MINIMUM_PAIRED_SAMPLES) {
            return "insufficient_samples";
        }
        double margin = WorkloadContract.PRACTICAL_EQUIVALENCE_MARGIN_PERCENT;
        if (lower > margin) return "candidate_better";
        if (upper < -margin) return "candidate_worse";
        if (lower >= -margin && upper <= margin) return "practically_equivalent";
        return "inconclusive";
    }

    private static void putNullInference(JSONObject output) throws Exception {
        output.put("median_paired_improvement_percent", JSONObject.NULL);
        output.put("mean_paired_improvement_percent", JSONObject.NULL);
        output.put("confidence_interval_95_percent", JSONObject.NULL);
        output.put("wins", 0);
        output.put("ties", 0);
        output.put("losses", 0);
        output.put("probability_of_superiority_percent", JSONObject.NULL);
        output.put("matched_rank_biserial_correlation", JSONObject.NULL);
        output.put("exact_sign_test_two_sided_p_value", JSONObject.NULL);
        output.put("order_bias_diagnostic", JSONObject.NULL);
    }

    private static double[] bootstrapMedianInterval(List<Double> values, String workloadId) {
        Random random = new Random(seedFor(values, workloadId));
        List<Double> bootstrap = new ArrayList<>(WorkloadContract.BOOTSTRAP_ITERATIONS);
        List<Double> sample = new ArrayList<>(values.size());
        for (int iteration = 0; iteration < WorkloadContract.BOOTSTRAP_ITERATIONS; ++iteration) {
            sample.clear();
            for (int index = 0; index < values.size(); ++index) {
                sample.add(values.get(random.nextInt(values.size())));
            }
            bootstrap.add(Phase2Metrics.median(sample));
        }
        Collections.sort(bootstrap);
        double alpha = (1.0 - WorkloadContract.CONFIDENCE_LEVEL) / 2.0;
        return new double[]{
                Phase2Metrics.percentile(bootstrap, alpha),
                Phase2Metrics.percentile(bootstrap, 1.0 - alpha)};
    }

    private static long seedFor(List<Double> values, String workloadId) {
        long seed = 0x414D4152414C3301L;
        seed = seed * 31L + workloadId.hashCode();
        for (double value : values) seed = seed * 31L + Double.doubleToLongBits(value);
        return seed;
    }

    private static double binomialProbability(int n, int k) {
        double coefficient = 1.0;
        for (int i = 1; i <= k; ++i) coefficient *= (n - (k - i)) / (double) i;
        return coefficient / Math.pow(2.0, n);
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.size();
    }
}
