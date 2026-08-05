package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SuiteHistory {
    enum SortOrder { NEWEST, IMPROVEMENT, DRIVER }

    private SuiteHistory() {}

    static List<SuiteRecord> scan(File filesDir) {
        List<File> candidates = new ArrayList<>();
        collectRunSuites(new File(filesDir, "runs"), candidates);
        collectImportedSuites(new File(filesDir, "imported-suites"), candidates);
        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());
        List<SuiteRecord> records = new ArrayList<>();
        for (File file : candidates) {
            if (records.size() >= Phase4Contract.MAX_LOCAL_SUITES) break;
            try {
                if (file.length() <= 0 || file.length() > Phase4Contract.MAX_IMPORTED_SUITE_BYTES) {
                    continue;
                }
                records.add(SuiteRecord.parse(file,
                        new JSONObject(ResultFiles.readUtf8(file))));
            } catch (Exception ignored) {
                // Invalid or unsupported files are intentionally excluded from the index.
            }
        }
        records.sort(Comparator.comparingLong((SuiteRecord item) -> item.finishedAtMs).reversed());
        return records;
    }

    static List<SuiteRecord> filter(List<SuiteRecord> input, String workloadId,
                                    String hardwareKey, SortOrder sortOrder,
                                    boolean includeBlocked) {
        List<SuiteRecord> output = new ArrayList<>();
        for (SuiteRecord item : input) {
            if (workloadId != null && !workloadId.isEmpty()
                    && !workloadId.equals(item.workloadId)) continue;
            if (hardwareKey != null && !hardwareKey.isEmpty()
                    && !hardwareKey.equals(item.hardwareKey)) continue;
            if (!includeBlocked && item.blockingValidity) continue;
            output.add(item);
        }
        if (sortOrder == SortOrder.IMPROVEMENT) {
            output.sort((left, right) -> compareScore(right.rankingScorePercent,
                    left.rankingScorePercent));
        } else if (sortOrder == SortOrder.DRIVER) {
            output.sort(Comparator.comparing(item -> item.candidateLabel.toLowerCase(Locale.US)));
        } else {
            output.sort(Comparator.comparingLong((SuiteRecord item) -> item.finishedAtMs).reversed());
        }
        return output;
    }

    static List<String> workloads(List<SuiteRecord> records) {
        Set<String> values = new LinkedHashSet<>();
        for (SuiteRecord record : records) values.add(record.workloadId);
        return new ArrayList<>(values);
    }

    static List<String> hardwareKeys(List<SuiteRecord> records) {
        Set<String> values = new LinkedHashSet<>();
        for (SuiteRecord record : records) values.add(record.hardwareKey);
        List<String> output = new ArrayList<>(values);
        Collections.sort(output);
        return output;
    }

    static JSONObject ranking(List<SuiteRecord> records, SuiteRecord reference) throws Exception {
        JSONObject output = new JSONObject();
        output.put("ranking_version", Phase4Contract.RANKING_VERSION);
        output.put("comparison_key", reference.comparisonKey());
        output.put("hardware_key", reference.hardwareKey);
        output.put("workload_id", reference.workloadId);
        output.put("workload_version", reference.workloadVersion);
        output.put("primary_metric", reference.primaryMetric);
        output.put("limitations", Phase4Contract.LIMITATION);
        if (!WorkloadContract.isPerformance(reference.workloadId)) {
            output.put("available", false);
            output.put("reason", "ranking_requires_performance_workload");
            output.put("entries", new JSONArray());
            return output;
        }

        Map<String, List<Double>> scores = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        Map<String, Integer> blocked = new HashMap<>();
        for (SuiteRecord item : records) {
            if (!reference.comparisonKey().equals(item.comparisonKey())) continue;
            labels.put(item.candidateSha256, item.candidateLabel);
            if (item.blockingValidity || !Double.isFinite(item.rankingScorePercent)) {
                blocked.put(item.candidateSha256,
                        blocked.getOrDefault(item.candidateSha256, 0) + 1);
                continue;
            }
            scores.computeIfAbsent(item.candidateSha256, ignored -> new ArrayList<>())
                    .add(item.rankingScorePercent);
        }
        List<JSONObject> entries = new ArrayList<>();
        for (Map.Entry<String, List<Double>> item : scores.entrySet()) {
            List<Double> values = item.getValue();
            JSONObject entry = new JSONObject();
            entry.put("candidate_sha256", item.getKey());
            entry.put("candidate_label", labels.get(item.getKey()));
            entry.put("valid_suite_count", values.size());
            entry.put("blocked_suite_count", blocked.getOrDefault(item.getKey(), 0));
            entry.put("median_improvement_percent", Phase2Metrics.median(values));
            entry.put("best_improvement_percent", Collections.max(values));
            entry.put("worst_improvement_percent", Collections.min(values));
            entries.add(entry);
        }
        entries.sort((left, right) -> Double.compare(
                right.optDouble("median_improvement_percent", Double.NEGATIVE_INFINITY),
                left.optDouble("median_improvement_percent", Double.NEGATIVE_INFINITY)));
        JSONArray encoded = new JSONArray();
        for (int index = 0; index < entries.size(); ++index) {
            entries.get(index).put("rank", index + 1);
            encoded.put(entries.get(index));
        }
        output.put("available", !entries.isEmpty());
        output.put("entries", encoded);
        return output;
    }

    private static void collectRunSuites(File root, List<File> output) {
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) return;
        for (File directory : directories) {
            File suite = new File(directory, "suite.json");
            if (suite.isFile()) output.add(suite);
        }
    }

    private static void collectImportedSuites(File root, List<File> output) {
        File[] files = root.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null) return;
        Collections.addAll(output, files);
    }

    private static int compareScore(double left, double right) {
        if (!Double.isFinite(left)) return Double.isFinite(right) ? -1 : 0;
        if (!Double.isFinite(right)) return 1;
        return Double.compare(left, right);
    }
}
