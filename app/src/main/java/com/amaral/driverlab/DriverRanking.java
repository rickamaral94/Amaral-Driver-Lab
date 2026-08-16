package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Pure ranking derivation. This class performs no filesystem or Android I/O. */
final class DriverRanking {
    static final String PRIMARY_ANCHOR_SHA256 =
            "a20ab1b7d7ca86b783cdd53d385b468313e4392f01ddbd8a5be69cfa84ff474a";
    static final String PRIMARY_ANCHOR_MESA_COMMIT = "1b2e70de00";
    static final int PRIMARY_ANCHOR_SCORE = 100;
    static final int MINIMUM_COMPLETE_SESSIONS = 3;
    static final double FALLBACK_NOISE_FLOOR = 0.03;
    static final int BOOTSTRAP_REPETITIONS = 2000;

    private DriverRanking() {}

    static RankingResult computeRanking(List<MeasurementRecord> records,
                                        JSONObject primaryAnchor,
                                        JSONObject stockAnchor,
                                        String epoch) throws Exception {
        RankingResult result = new RankingResult();
        result.epoch = epoch;
        String anchorSha = primaryAnchor == null
                ? PRIMARY_ANCHOR_SHA256
                : primaryAnchor.optString("binary_sha256", PRIMARY_ANCHOR_SHA256);
        String localDevice = primaryAnchor == null ? ""
                : primaryAnchor.optString("device_fingerprint_sha256", "");

        List<MeasurementRecord> localEpoch = new ArrayList<>();
        List<MeasurementRecord> nullTests = new ArrayList<>();
        for (MeasurementRecord record : records) {
            boolean local = !record.imported() && !record.foreignDevice()
                    && (localDevice.isEmpty() || localDevice.equals(record.deviceFingerprint()));
            if (local && epoch.equals(record.epoch())) {
                localEpoch.add(record);
                if (record.nullTest() && record.completeAbBa() && record.thermalGatePassed()) {
                    nullTests.add(record);
                }
            }
        }
        result.noiseFloor = noiseFloor(nullTests);
        result.anchorDrift = anchorDrift(localEpoch, anchorSha, result.noiseFloor);
        if (nullTests.isEmpty()) {
            result.blocked = true;
            result.blockedReason = "null_test_missing";
            collectVisibleIneligible(records, result, "ranking_blocked_null_test_missing");
            return result;
        }
        MeasurementRecord latestNull = latest(nullTests);
        ScoreEstimate nullEstimate = score(latestNull, false);
        if (nullEstimate == null || nullEstimate.ciLow > 100.0 || nullEstimate.ciHigh < 100.0) {
            result.blocked = true;
            result.blockedReason = "null_test_ci95_does_not_contain_100";
            collectVisibleIneligible(records, result, result.blockedReason);
            return result;
        }

        Map<String, List<MeasurementRecord>> byDriver = new LinkedHashMap<>();
        for (MeasurementRecord record : records) {
            String sha = record.driverBinarySha();
            if (!sha.isEmpty()) byDriver.computeIfAbsent(sha, ignored -> new ArrayList<>()).add(record);
        }
        for (Map.Entry<String, List<MeasurementRecord>> group : byDriver.entrySet()) {
            evaluateDriver(group.getKey(), group.getValue(), localEpoch, anchorSha,
                    primaryAnchor, stockAnchor, epoch, localDevice, result);
        }

        result.ranked.sort(Comparator
                .comparingInt((DriverEntry entry) -> entry.score == null ? Integer.MIN_VALUE
                        : entry.score).reversed()
                .thenComparingDouble(entry -> intervalWidth(entry))
                .thenComparing(Comparator.comparingInt((DriverEntry entry) -> entry.n).reversed())
                .thenComparing(Comparator.comparingLong(
                        (DriverEntry entry) -> entry.measuredAtMs).reversed()));
        assignRanks(result.ranked, result.noiseFloor);
        if (result.ranked.size() > 10) {
            result.ranked.subList(10, result.ranked.size()).clear();
        }
        addReferenceLines(result, primaryAnchor, stockAnchor, anchorSha,
                records, epoch, localDevice);
        return result;
    }

    private static void evaluateDriver(String sha, List<MeasurementRecord> all,
                                       List<MeasurementRecord> localEpoch, String anchorSha,
                                       JSONObject primaryAnchor, JSONObject stockAnchor,
                                       String epoch, String localDevice, RankingResult output)
            throws Exception {
        boolean hasCampaignMeasurement = false;
        for (MeasurementRecord record : all) hasCampaignMeasurement |= !record.nullTest();
        if (!hasCampaignMeasurement) return;
        all.sort(Comparator.comparingLong(MeasurementRecord::timestampMs));
        MeasurementRecord newest = all.get(all.size() - 1);
        JSONObject identity = newest.candidateIdentity();
        String name = text(identity, "driver_name", "Driver " + shortSha(sha));
        String api = text(identity, "api_version", "");
        JSONArray history = history(all, epoch, localDevice);
        JSONArray reasons = new JSONArray();

        List<MeasurementRecord> current = new ArrayList<>();
        for (MeasurementRecord record : all) {
            if (!record.imported() && !record.foreignDevice()
                    && epoch.equals(record.epoch())
                    && (localDevice.isEmpty() || localDevice.equals(record.deviceFingerprint()))) {
                current.add(record);
            }
        }
        List<JSONObject> disqualifications = disqualifications(current);
        if (!disqualifications.isEmpty()) {
            for (JSONObject reason : disqualifications) reasons.put(reason);
            output.disqualified.add(emptyEntry(DriverEntry.State.DISQUALIFIED, sha, name, api,
                    newest, reasons, history));
            return;
        }

        String vendor = identity == null ? "unknown"
                : identity.optString("runtime_vendor", "unknown");
        if (!"mesa".equals(vendor)) {
            reasons.put(reason("identity_not_mesa", null, newest.id()));
        }
        int completeSessions = 0;
        List<MeasurementRecord> official = new ArrayList<>();
        String requiredWorkloadSet = primaryAnchor == null ? ""
                : primaryAnchor.optString("workload_set_sha256", "");
        String requiredMesaCommit = primaryAnchor == null
                ? PRIMARY_ANCHOR_MESA_COMMIT
                : primaryAnchor.optString("mesa_commit", PRIMARY_ANCHOR_MESA_COMMIT);
        for (MeasurementRecord record : current) {
            if (!record.thermalGatePassed()) continue;
            if (!requiredWorkloadSet.isEmpty() && !requiredWorkloadSet.equals(
                    record.raw.optString("workload_set_sha256"))) continue;
            if (record.completeAbBa()) completeSessions++;
            if (hasPrimaryAnchor(record, anchorSha, requiredMesaCommit)) official.add(record);
        }
        if (completeSessions < MINIMUM_COMPLETE_SESSIONS) {
            reasons.put(reason("fewer_than_3_complete_abba_sessions", null, newest.id())
                    .put("observed", completeSessions)
                    .put("required", MINIMUM_COMPLETE_SESSIONS));
        }
        boolean thermalFailure = false;
        boolean workloadMismatch = false;
        for (MeasurementRecord record : current) {
            thermalFailure |= !record.thermalGatePassed();
            workloadMismatch |= !requiredWorkloadSet.isEmpty() && !requiredWorkloadSet.equals(
                    record.raw.optString("workload_set_sha256"));
        }
        if (thermalFailure) reasons.put(reason("thermal_gate_violated", null, newest.id()));
        if (workloadMismatch) reasons.put(reason("workload_set_or_version_mismatch", null,
                newest.id()));
        if (official.isEmpty()) reasons.put(reason("session_without_primary_anchor", null,
                newest.id()));
        if (newest.imported() || newest.foreignDevice()
                || (!localDevice.isEmpty() && !localDevice.equals(newest.deviceFingerprint()))) {
            reasons.put(reason("foreign_device_history", null, newest.id()));
        }
        if (!epoch.equals(newest.epoch())) {
            reasons.put(reason("previous_epoch", null, newest.id()));
        }
        if (reasons.length() > 0) {
            output.ineligible.add(emptyEntry(DriverEntry.State.INELIGIBLE, sha, name, api,
                    newest, reasons, history));
            return;
        }

        MeasurementRecord measurement = latest(official);
        ScoreEstimate estimate = sha.equalsIgnoreCase(anchorSha)
                ? ScoreEstimate.anchor(measurement) : score(measurement, false);
        if (estimate == null) {
            reasons.put(reason("raw_time_or_frametime_series_missing", null, measurement.id()));
            output.ineligible.add(emptyEntry(DriverEntry.State.INELIGIBLE, sha, name, api,
                    measurement, reasons, history));
            return;
        }
        Integer vsStock = stockScore(all, stockAnchor, epoch, localDevice);
        appendStockAdjudicationAndExclusions(reasons, all, epoch, localDevice);
        DriverEntry entry = new DriverEntry(DriverEntry.State.RANKED, sha, name, api,
                estimate.score, vsStock, estimate.sustained, estimate.low,
                estimate.ciLow, estimate.ciHigh, completeSessions, measurement.timestampMs(),
                measurement.anchorConfidence(), reasons, history);
        for (MeasurementRecord session : official) {
            ScoreEstimate sessionEstimate = sha.equalsIgnoreCase(anchorSha)
                    ? ScoreEstimate.anchor(session) : score(session, false);
            if (sessionEstimate != null) entry.sessionScores.add((double) sessionEstimate.score);
        }
        output.ranked.add(entry);
    }

    private static List<JSONObject> disqualifications(List<MeasurementRecord> records)
            throws Exception {
        List<JSONObject> output = new ArrayList<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<JSONObject> observed = new ArrayList<>();
        for (MeasurementRecord record : records) {
            if (!record.thermalGatePassed()) continue;
            JSONObject referenceIdentity = record.referenceIdentity();
            boolean stockComparison = referenceIdentity != null
                    && "qualcomm_proprietary".equals(
                    referenceIdentity.optString("runtime_vendor"));
            JSONArray failures = record.failures();
            for (int index = 0; failures != null && index < failures.length(); index++) {
                JSONObject failure = failures.optJSONObject(index);
                if (failure == null || !isDisqualifying(failure.optString("code"))) continue;
                if (stockComparison && "image_hash_mismatch".equals(
                        failure.optString("code"))) continue;
                JSONObject item = new JSONObject(failure.toString())
                        .put("session_id", record.id());
                String key = failure.optString("code") + "/" + failure.optString("workload_id");
                occurrences.put(key, occurrences.getOrDefault(key, 0) + 1);
                item.put("reproduction_key", key);
                observed.add(item);
            }
            JSONArray workloads = record.workloads();
            for (int index = 0; workloads != null && index < workloads.length(); index++) {
                JSONObject workload = workloads.optJSONObject(index);
                if (workload == null) continue;
                String candidate = workload.optString("candidate_image_hash", "");
                String anchor = workload.optString("anchor_image_hash", "");
                if (!stockComparison && !candidate.isEmpty() && !anchor.isEmpty()
                        && !candidate.equals(anchor)) {
                    String key = "image_hash_mismatch/" + workload.optString("workload_id");
                    occurrences.put(key, occurrences.getOrDefault(key, 0) + 1);
                    observed.add(reason("image_hash_mismatch",
                            workload.optString("workload_id"), record.id())
                            .put("reproduction_key", key));
                }
            }
        }
        for (JSONObject item : observed) {
            item.put("reproducibility", occurrences.getOrDefault(
                    item.optString("reproduction_key"), 0) > 1 ? "reprodutível" : "isolado");
            item.remove("reproduction_key");
            output.add(item);
        }
        return output;
    }

    private static boolean isDisqualifying(String code) {
        return "image_hash_mismatch".equals(code) || "device_lost".equals(code)
                || "runner_crash".equals(code) || "workload_timeout".equals(code)
                || "workload_abort".equals(code) || "workload_incomplete".equals(code)
                || "fatal_validation_error".equals(code);
    }

    private static boolean hasPrimaryAnchor(MeasurementRecord record, String anchorSha,
                                            String mesaCommit) {
        JSONObject reference = record.referenceIdentity();
        if (reference == null || !anchorSha.equalsIgnoreCase(
                reference.optString("binary_sha256"))) return false;
        return mesaCommit == null || mesaCommit.isEmpty()
                || mesaCommit.equalsIgnoreCase(reference.optString("mesa_commit"));
    }

    private static Integer stockScore(List<MeasurementRecord> records, JSONObject stockAnchor,
                                      String epoch, String localDevice) {
        if (stockAnchor == null) return null;
        List<MeasurementRecord> eligible = new ArrayList<>();
        for (MeasurementRecord record : records) {
            JSONObject reference = record.referenceIdentity();
            if (reference == null || !"qualcomm_proprietary".equals(
                    reference.optString("runtime_vendor"))) continue;
            if (!epoch.equals(record.epoch()) || record.imported() || record.foreignDevice()
                    || !record.thermalGatePassed()
                    || !record.raw.optBoolean("measurement_clock_equivalent", false)
                    || (!localDevice.isEmpty() && !localDevice.equals(record.deviceFingerprint()))) {
                continue;
            }
            eligible.add(record);
        }
        if (eligible.isEmpty()) return null;
        ScoreEstimate estimate = score(latest(eligible), true);
        return estimate == null ? null : estimate.score;
    }

    private static void appendStockAdjudicationAndExclusions(JSONArray reasons,
                                                              List<MeasurementRecord> records,
                                                              String epoch, String localDevice)
            throws Exception {
        for (MeasurementRecord record : records) {
            JSONObject reference = record.referenceIdentity();
            if (reference == null || !"qualcomm_proprietary".equals(
                    reference.optString("runtime_vendor"))
                    || !epoch.equals(record.epoch())
                    || (!localDevice.isEmpty() && !localDevice.equals(
                    record.deviceFingerprint()))) continue;
            JSONArray excluded = new JSONArray();
            boolean hashDiscrepancy = false;
            JSONArray workloads = record.workloads();
            for (int index = 0; workloads != null && index < workloads.length(); index++) {
                JSONObject workload = workloads.optJSONObject(index);
                if (workload == null) continue;
                boolean hasTime = positive(workload.optDouble("anchor_time_ms", Double.NaN))
                        && positive(workload.optDouble("candidate_time_ms", Double.NaN));
                boolean needsLow = workload.optBoolean("included_in_low", false);
                boolean hasLow = !needsLow || (positive(p99(
                        workload.optJSONArray("anchor_frame_times_ms")))
                        && positive(p99(workload.optJSONArray("candidate_frame_times_ms"))));
                if (!hasTime || !hasLow) excluded.put(workload.optString("workload_id"));
                String candidateHash = workload.optString("candidate_image_hash", "");
                String stockHash = workload.optString("anchor_image_hash", "");
                hashDiscrepancy |= !candidateHash.isEmpty() && !stockHash.isEmpty()
                        && !candidateHash.equals(stockHash);
            }
            if (excluded.length() > 0) reasons.put(new JSONObject()
                    .put("code", "vs_stock_intersection_excluded_workloads")
                    .put("session_id", record.id()).put("workloads", excluded));
            if (hashDiscrepancy) reasons.put(new JSONObject()
                    .put("code", "stock_vs_turnip_hash_discrepancy_human_adjudication")
                    .put("session_id", record.id())
                    .put("automatic_disqualification", false));
        }
    }

    private static ScoreEstimate score(MeasurementRecord record, boolean allowIntersection) {
        JSONArray workloads = record.workloads();
        List<Double> sustainedRatios = new ArrayList<>();
        List<Double> lowRatios = new ArrayList<>();
        for (int index = 0; workloads != null && index < workloads.length(); index++) {
            JSONObject workload = workloads.optJSONObject(index);
            if (workload == null) continue;
            double anchorTime = workload.optDouble("anchor_time_ms", Double.NaN);
            double candidateTime = workload.optDouble("candidate_time_ms", Double.NaN);
            double anchorP99 = p99(workload.optJSONArray("anchor_frame_times_ms"));
            double candidateP99 = p99(workload.optJSONArray("candidate_frame_times_ms"));
            boolean sustainedWorkload = workload.optBoolean("included_in_sustained", true);
            boolean lowWorkload = workload.optBoolean("included_in_low", false);
            if (sustainedWorkload) {
                if (!positive(anchorTime) || !positive(candidateTime)) {
                    if (allowIntersection) continue;
                    return null;
                }
                sustainedRatios.add(anchorTime / candidateTime);
            }
            if (lowWorkload) {
                if (!positive(anchorP99) || !positive(candidateP99)) {
                    if (allowIntersection) continue;
                    return null;
                }
                lowRatios.add(anchorP99 / candidateP99);
            }
        }
        if (sustainedRatios.isEmpty() || lowRatios.isEmpty()) return null;
        double sustained = geometricMean(sustainedRatios);
        double low = geometricMean(lowRatios);
        // The 1:1 geometric combination is intentionally the only preference-free default.
        int point = (int) Math.round(100.0 * Math.sqrt(sustained * low));
        double[] interval = bootstrap(sustainedRatios, lowRatios, record.id().hashCode());
        JSONObject declared = record.raw.optJSONObject("score_ci95");
        if (declared != null) {
            interval[0] = declared.optDouble("low", interval[0]);
            interval[1] = declared.optDouble("high", interval[1]);
        }
        return new ScoreEstimate(point, sustained, low, interval[0], interval[1]);
    }

    private static double[] bootstrap(List<Double> sustained, List<Double> low, long seed) {
        if (sustained.size() == 1 && low.size() == 1) {
            double point = 100.0 * Math.sqrt(sustained.get(0) * low.get(0));
            return new double[]{point, point};
        }
        Random random = new Random(seed);
        List<Double> values = new ArrayList<>(BOOTSTRAP_REPETITIONS);
        for (int iteration = 0; iteration < BOOTSTRAP_REPETITIONS; iteration++) {
            List<Double> sampledS = new ArrayList<>();
            List<Double> sampledL = new ArrayList<>();
            for (int index = 0; index < sustained.size(); index++) {
                sampledS.add(sustained.get(random.nextInt(sustained.size())));
            }
            for (int index = 0; index < low.size(); index++) {
                sampledL.add(low.get(random.nextInt(low.size())));
            }
            values.add(100.0 * Math.sqrt(geometricMean(sampledS) * geometricMean(sampledL)));
        }
        Collections.sort(values);
        return new double[]{percentile(values, 0.025), percentile(values, 0.975)};
    }

    private static double noiseFloor(List<MeasurementRecord> nullTests) {
        List<Double> deviations = new ArrayList<>();
        for (MeasurementRecord record : nullTests) {
            JSONArray workloads = record.workloads();
            for (int index = 0; workloads != null && index < workloads.length(); index++) {
                JSONObject workload = workloads.optJSONObject(index);
                if (workload == null) continue;
                double anchor = workload.optDouble("anchor_time_ms", Double.NaN);
                double candidate = workload.optDouble("candidate_time_ms", Double.NaN);
                if (positive(anchor) && positive(candidate)) {
                    deviations.add(Math.abs(candidate / anchor - 1.0));
                }
            }
        }
        if (deviations.size() < 5) return FALLBACK_NOISE_FLOOR;
        Collections.sort(deviations);
        return percentile(deviations, 0.95);
    }

    private static JSONObject anchorDrift(List<MeasurementRecord> records, String anchorSha,
                                          double noiseFloor) throws Exception {
        List<AnchorPoint> points = new ArrayList<>();
        for (MeasurementRecord record : records) {
            JSONObject reference = record.referenceIdentity();
            if (reference == null || !anchorSha.equalsIgnoreCase(
                    reference.optString("binary_sha256"))) continue;
            List<Double> times = new ArrayList<>();
            JSONArray workloads = record.workloads();
            for (int index = 0; workloads != null && index < workloads.length(); index++) {
                JSONObject workload = workloads.optJSONObject(index);
                if (workload == null || !workload.optBoolean("included_in_sustained", true)) continue;
                double time = workload.optDouble("anchor_time_ms", Double.NaN);
                if (positive(time)) times.add(time);
            }
            if (!times.isEmpty()) points.add(new AnchorPoint(
                    record.timestampMs(), geometricMean(times), record.id()));
        }
        points.sort(Comparator.comparingLong(point -> point.timestamp));
        if (points.size() < 2) return new JSONObject()
                .put("status", "insufficient_anchor_history")
                .put("point_count", points.size());
        AnchorPoint first = points.get(0);
        AnchorPoint last = points.get(points.size() - 1);
        double ratio = last.absoluteTime / first.absoluteTime;
        return new JSONObject()
                .put("status", Math.abs(ratio - 1.0) > noiseFloor
                        ? "device_drift_observed" : "stable_within_noise_floor")
                .put("point_count", points.size())
                .put("first_absolute_anchor_time_ms", first.absoluteTime)
                .put("latest_absolute_anchor_time_ms", last.absoluteTime)
                .put("latest_over_first_ratio", ratio)
                .put("first_session_id", first.sessionId)
                .put("latest_session_id", last.sessionId)
                .put("changes_ranking_score", false);
    }

    static double noiseFloorForRecords(List<MeasurementRecord> records, String device,
                                       String epoch) {
        List<MeasurementRecord> nullTests = new ArrayList<>();
        for (MeasurementRecord record : records) {
            if (record.nullTest() && record.completeAbBa() && record.thermalGatePassed()
                    && !record.imported() && !record.foreignDevice()
                    && device.equals(record.deviceFingerprint())
                    && epoch.equals(record.epoch())) nullTests.add(record);
        }
        return noiseFloor(nullTests);
    }

    private static void assignRanks(List<DriverEntry> ranked, double noiseFloor) {
        int position = 1;
        for (int index = 0; index < ranked.size(); index++) {
            DriverEntry current = ranked.get(index);
            if (index == 0) current.rank = 1;
            else {
                DriverEntry previous = ranked.get(index - 1);
                double scale = Math.max(1.0, previous.score == null ? 1.0 : previous.score);
                boolean noiseTie = Math.abs(previous.score - current.score) / scale <= noiseFloor;
                boolean distributionTie = mannWhitneyDoesNotReject(
                        previous.sessionScores, current.sessionScores);
                current.rank = noiseTie || distributionTie ? previous.rank : position;
            }
            position++;
        }
    }

    private static boolean mannWhitneyDoesNotReject(List<Double> left, List<Double> right) {
        if (left.size() < 2 || right.size() < 2) return false;
        List<RankedSample> combined = new ArrayList<>();
        for (double value : left) combined.add(new RankedSample(value, true));
        for (double value : right) combined.add(new RankedSample(value, false));
        combined.sort(Comparator.comparingDouble(sample -> sample.value));
        double leftRankSum = 0.0;
        int index = 0;
        while (index < combined.size()) {
            int end = index + 1;
            while (end < combined.size()
                    && Double.compare(combined.get(end).value, combined.get(index).value) == 0) {
                end++;
            }
            double averageRank = ((index + 1) + end) / 2.0;
            for (int cursor = index; cursor < end; cursor++) {
                if (combined.get(cursor).left) leftRankSum += averageRank;
            }
            index = end;
        }
        double n1 = left.size();
        double n2 = right.size();
        double u1 = leftRankSum - n1 * (n1 + 1.0) / 2.0;
        double mean = n1 * n2 / 2.0;
        double standardDeviation = Math.sqrt(n1 * n2 * (n1 + n2 + 1.0) / 12.0);
        if (standardDeviation == 0.0) return true;
        double z = Math.abs((u1 - mean) / standardDeviation);
        return z <= 1.959963984540054; // two-sided alpha=0.05
    }

    private static void addReferenceLines(RankingResult result, JSONObject primary,
                                          JSONObject stock, String anchorSha,
                                          List<MeasurementRecord> records,
                                          String epoch, String localDevice) throws Exception {
        JSONObject identity = primary == null ? new JSONObject() : primary;
        List<MeasurementRecord> anchorRecords = new ArrayList<>();
        for (MeasurementRecord record : records) if (anchorSha.equalsIgnoreCase(
                record.driverBinarySha())) anchorRecords.add(record);
        Integer anchorVsStock = stockScore(anchorRecords, stock, epoch, localDevice);
        result.references.add(new DriverEntry(DriverEntry.State.REFERENCE, anchorSha,
                identity.optString("driver_name", "Âncora Turnip v3 auditada"),
                identity.optString("api_version", ""), 100, anchorVsStock,
                1.0, 1.0, 100.0, 100.0, 0, 0L,
                MeasurementRecord.CONFIDENCE_CO_MEASURED, new JSONArray(), new JSONArray()));
        if (stock != null) {
            result.references.add(new DriverEntry(DriverEntry.State.REFERENCE,
                    stock.optString("binary_sha256", "system"),
                    stock.optString("driver_name", "Blob Qualcomm do aparelho"),
                    stock.optString("api_version", ""), null, 100,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0L,
                    MeasurementRecord.CONFIDENCE_CO_MEASURED,
                    new JSONArray(), new JSONArray()));
        }
    }

    private static void collectVisibleIneligible(List<MeasurementRecord> records,
                                                 RankingResult output, String code)
            throws Exception {
        Map<String, MeasurementRecord> latest = new LinkedHashMap<>();
        for (MeasurementRecord record : records) {
            MeasurementRecord prior = latest.get(record.driverBinarySha());
            if (prior == null || record.timestampMs() > prior.timestampMs()) {
                latest.put(record.driverBinarySha(), record);
            }
        }
        for (MeasurementRecord record : latest.values()) {
            JSONObject identity = record.candidateIdentity();
            output.ineligible.add(emptyEntry(DriverEntry.State.INELIGIBLE,
                    record.driverBinarySha(), text(identity, "driver_name", "Driver"),
                    text(identity, "api_version", ""), record,
                    new JSONArray().put(reason(code, null, record.id())),
                    history(Collections.singletonList(record), output.epoch, "")));
        }
    }

    private static DriverEntry emptyEntry(DriverEntry.State state, String sha, String name,
                                          String api, MeasurementRecord newest,
                                          JSONArray reasons, JSONArray history) {
        return new DriverEntry(state, sha, name, api, null, null,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                newest.timestampMs(), newest.anchorConfidence(), reasons, history);
    }

    private static JSONArray history(List<MeasurementRecord> records, String epoch,
                                     String localDevice) throws Exception {
        JSONArray output = new JSONArray();
        for (MeasurementRecord record : records) {
            output.put(new JSONObject()
                    .put("measurement_id", record.id())
                    .put("timestamp_ms", record.timestampMs())
                    .put("epoch_sha256", record.epoch())
                    .put("current_epoch", epoch.equals(record.epoch()))
                    .put("foreign_device", record.foreignDevice()
                            || (!localDevice.isEmpty()
                            && !localDevice.equals(record.deviceFingerprint())))
                    .put("anchor_confidence", record.anchorConfidence())
                    .put("formula_version", record.raw.optInt("formula_version",
                            MeasurementRecord.CURRENT_FORMULA_VERSION)));
        }
        return output;
    }

    private static JSONObject reason(String code, String workload, String session)
            throws Exception {
        return new JSONObject().put("code", code)
                .put("workload_id", workload == null ? JSONObject.NULL : workload)
                .put("session_id", session == null ? JSONObject.NULL : session);
    }

    private static String text(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }

    private static MeasurementRecord latest(List<MeasurementRecord> records) {
        return Collections.max(records, Comparator.comparingLong(MeasurementRecord::timestampMs));
    }

    private static double geometricMean(List<Double> values) {
        double log = 0.0;
        for (double value : values) log += Math.log(value);
        return Math.exp(log / values.size());
    }

    private static double p99(JSONArray values) {
        if (values == null || values.length() == 0) return Double.NaN;
        List<Double> finite = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            double value = values.optDouble(index, Double.NaN);
            if (positive(value)) finite.add(value);
        }
        if (finite.isEmpty()) return Double.NaN;
        Collections.sort(finite);
        return percentile(finite, 0.99);
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.size() == 1) return sorted.get(0);
        double position = q * (sorted.size() - 1);
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        double fraction = position - low;
        return sorted.get(low) * (1.0 - fraction) + sorted.get(high) * fraction;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static double intervalWidth(DriverEntry entry) {
        return Double.isFinite(entry.ciLow) && Double.isFinite(entry.ciHigh)
                ? entry.ciHigh - entry.ciLow : Double.POSITIVE_INFINITY;
    }

    private static String shortSha(String sha) {
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private static final class ScoreEstimate {
        final int score;
        final double sustained;
        final double low;
        final double ciLow;
        final double ciHigh;

        ScoreEstimate(int score, double sustained, double low, double ciLow, double ciHigh) {
            this.score = score;
            this.sustained = sustained;
            this.low = low;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
        }

        static ScoreEstimate anchor(MeasurementRecord record) {
            return new ScoreEstimate(PRIMARY_ANCHOR_SCORE, 1.0, 1.0,
                    100.0, 100.0);
        }
    }

    private static final class RankedSample {
        final double value;
        final boolean left;
        RankedSample(double value, boolean left) {
            this.value = value;
            this.left = left;
        }
    }

    private static final class AnchorPoint {
        final long timestamp;
        final double absoluteTime;
        final String sessionId;
        AnchorPoint(long timestamp, double absoluteTime, String sessionId) {
            this.timestamp = timestamp;
            this.absoluteTime = absoluteTime;
            this.sessionId = sessionId;
        }
    }
}
