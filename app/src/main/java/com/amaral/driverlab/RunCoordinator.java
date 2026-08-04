package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RunCoordinator {
    static final int MODE_SYSTEM = 0;
    static final int MODE_CUSTOM = 1;
    static final int MODE_AB = 2;

    interface Listener {
        void onStatus(String message);
        void onComplete(File reportFile, JSONObject report);
        void onFailure(String message, Throwable error);
    }

    private static final class Phase {
        final boolean custom;
        final int round;
        final String label;

        Phase(boolean custom, int round) {
            this.custom = custom;
            this.round = round;
            this.label = custom ? "candidate" : "system";
        }
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DriverPackage candidate;
    private final int mode;
    private final int rounds;
    private final int warmupSeconds;
    private final int measureSeconds;
    private final String workloadId;
    private final String traceId;
    private final int pixelTolerance;
    private final int maximumDivergentBlocks;
    private final Listener listener;
    private final JSONObject executionContext;
    private final List<Phase> phases = new ArrayList<>();
    private final JSONArray phaseResults = new JSONArray();

    private File suiteDirectory;
    private long suiteStartedAt;
    private int phaseIndex;
    private long phaseDeadlineElapsed;
    private long phaseLaunchedElapsed;
    private File currentResultFile;

    RunCoordinator(Activity activity, DriverPackage candidate, int mode, int rounds,
                   int warmupSeconds, int measureSeconds, String workloadId, String traceId,
                   int pixelTolerance, int maximumDivergentBlocks, Listener listener) {
        this(activity, candidate, mode, rounds, warmupSeconds, measureSeconds, workloadId,
                traceId, pixelTolerance, maximumDivergentBlocks, null, listener);
    }

    RunCoordinator(Activity activity, DriverPackage candidate, int mode, int rounds,
                   int warmupSeconds, int measureSeconds, String workloadId, String traceId,
                   int pixelTolerance, int maximumDivergentBlocks, JSONObject executionContext,
                   Listener listener) {
        this.activity = activity;
        this.candidate = candidate;
        this.mode = mode;
        this.rounds = Math.max(1, Math.min(rounds, 10));
        this.warmupSeconds = Math.max(0, Math.min(warmupSeconds, 30));
        this.measureSeconds = Math.max(1, Math.min(measureSeconds, 120));
        if (!WorkloadContract.isSupported(workloadId)) {
            throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
        }
        this.workloadId = workloadId;
        this.traceId = WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)
                ? (TraceReplayContract.isSupported(traceId) ? traceId
                : TraceReplayContract.MIXED_TRACE_ID) : TraceReplayContract.MIXED_TRACE_ID;
        this.pixelTolerance = Math.max(0, Math.min(pixelTolerance, 255));
        this.maximumDivergentBlocks = Math.max(0, maximumDivergentBlocks);
        this.executionContext = executionContext == null ? null
                : new JSONObject(executionContext.toString());
        this.listener = listener;
    }

    void start() {
        try {
            if ((mode == MODE_CUSTOM || mode == MODE_AB)
                    && (candidate == null || !candidate.isUsable())) {
                throw new IllegalStateException("Importe um driver válido para este modo");
            }
            suiteStartedAt = System.currentTimeMillis();
            suiteDirectory = new File(new File(activity.getFilesDir(), "runs"),
                    "suite-" + suiteStartedAt);
            if (!suiteDirectory.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar a pasta da suíte");
            }
            buildPlan();
            launchNext();
        } catch (Throwable error) {
            listener.onFailure("Não foi possível iniciar a suíte", error);
        }
    }

    private void buildPlan() {
        if (mode == MODE_SYSTEM) {
            for (int round = 1; round <= rounds; ++round) phases.add(new Phase(false, round));
            return;
        }
        if (mode == MODE_CUSTOM) {
            for (int round = 1; round <= rounds; ++round) phases.add(new Phase(true, round));
            return;
        }
        for (int round = 1; round <= rounds; ++round) {
            // Alternating AB/BA reduces bias from temperature drift and run order.
            boolean candidateFirst = round % 2 == 0;
            phases.add(new Phase(candidateFirst, round));
            phases.add(new Phase(!candidateFirst, round));
        }
    }

    private void launchNext() {
        if (phaseIndex >= phases.size()) {
            finishSuite();
            return;
        }
        Phase phase = phases.get(phaseIndex);
        String fileName = String.format(Locale.US, "phase-%02d-%s-r%d.json",
                phaseIndex + 1, phase.label, phase.round);
        currentResultFile = new File(suiteDirectory, fileName);
        String workloadLabel = WorkloadContract.labelFor(workloadId);
        listener.onStatus("Executando " + (phaseIndex + 1) + "/" + phases.size()
                + " · " + workloadLabel + " · rodada " + phase.round + " · "
                + (phase.custom ? candidate.displayName() : "driver do sistema"));

        Intent intent = new Intent(activity, RunnerActivity.class);
        intent.putExtra(RunnerActivity.EXTRA_RESULT_PATH, currentResultFile.getAbsolutePath());
        intent.putExtra(RunnerActivity.EXTRA_PHASE_LABEL, phase.label);
        intent.putExtra(RunnerActivity.EXTRA_ROUND, phase.round);
        intent.putExtra(RunnerActivity.EXTRA_WARMUP_SECONDS, warmupSeconds);
        intent.putExtra(RunnerActivity.EXTRA_MEASURE_SECONDS, measureSeconds);
        intent.putExtra(RunnerActivity.EXTRA_WORKLOAD_ID, workloadId);
        intent.putExtra(RunnerActivity.EXTRA_TRACE_ID, traceId);
        intent.putExtra(RunnerActivity.EXTRA_WORKLOAD_VERSION,
                WorkloadContract.versionFor(workloadId));
        intent.putExtra(RunnerActivity.EXTRA_PIXEL_TOLERANCE, pixelTolerance);
        intent.putExtra(RunnerActivity.EXTRA_MAX_DIVERGENT_BLOCKS, maximumDivergentBlocks);
        if (phase.custom) {
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_DIR, candidate.directory.getAbsolutePath());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_NAME, candidate.libraryName);
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_META, candidate.metadata.toString());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_SHA, candidate.sha256);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        phaseLaunchedElapsed = SystemClock.elapsedRealtime();
        long timeoutSeconds = WorkloadContract.timeoutSeconds(
                workloadId, warmupSeconds, measureSeconds);
        phaseDeadlineElapsed = phaseLaunchedElapsed + timeoutSeconds * 1000L;
        activity.startActivity(intent);
        handler.postDelayed(this::pollCurrent, 500);
    }

    private void pollCurrent() {
        try {
            if (currentResultFile.isFile()) {
                phaseResults.put(new JSONObject(ResultFiles.readUtf8(currentResultFile)));
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1200);
                return;
            }
            if (runnerExitedUnexpectedly()) {
                killTimedOutRunner();
                recordSyntheticFailure("crash", "runner_crash");
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1200);
                return;
            }
            if (SystemClock.elapsedRealtime() >= phaseDeadlineElapsed) {
                killTimedOutRunner();
                recordSyntheticFailure("timeout", "runner_timeout");
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1200);
                return;
            }
            handler.postDelayed(this::pollCurrent, 500);
        } catch (Throwable error) {
            listener.onFailure("Falha ao ler o resultado da fase", error);
        }
    }

    private boolean runnerExitedUnexpectedly() {
        if (SystemClock.elapsedRealtime() - phaseLaunchedElapsed < 2000L) return false;
        try {
            File stateFile = new File(currentResultFile.getAbsolutePath() + ".state");
            if (!stateFile.isFile()) return false;
            JSONObject state = new JSONObject(ResultFiles.readUtf8(stateFile));
            if (!"started".equals(state.optString("state"))) return false;
            int pid = state.optInt("pid", -1);
            return pid > 0 && !new File("/proc/" + pid).exists();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recordSyntheticFailure(String failureType, String error) throws Exception {
        Phase phase = phases.get(phaseIndex);
        JSONObject failure = new JSONObject();
        failure.put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION);
        failure.put("success", false);
        failure.put("phase", phase.label);
        failure.put("driver_mode", phase.custom ? "custom" : "system");
        failure.put("round", phase.round);
        failure.put("workload_id", workloadId);
        failure.put("workload_version", WorkloadContract.versionFor(workloadId));
        if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) failure.put("trace_id", traceId);
        failure.put("failure_type", failureType);
        failure.put("failure_stage", "runner_process");
        failure.put("error", error);
        failure.put("finished_at_ms", System.currentTimeMillis());
        ResultFiles.writeAtomic(currentResultFile, failure.toString(2));
        phaseResults.put(failure);
    }

    private void killTimedOutRunner() {
        try {
            File stateFile = new File(currentResultFile.getAbsolutePath() + ".state");
            if (!stateFile.isFile()) return;
            int pid = new JSONObject(ResultFiles.readUtf8(stateFile)).optInt("pid", -1);
            if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
        } catch (Exception ignored) {
            // The failure remains recorded even if the stale runner cannot be killed.
        }
    }

    private void finishSuite() {
        try {
            JSONObject report = new JSONObject();
            report.put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION);
            report.put("suite_id", suiteDirectory.getName());
            report.put("app_version", BuildConfig.VERSION_NAME);
            report.put("started_at_ms", suiteStartedAt);
            report.put("finished_at_ms", System.currentTimeMillis());
            report.put("mode", modeName());
            report.put("rounds", rounds);
            report.put("order_policy", mode == MODE_AB ? "AB/BA alternating" : "single driver");
            report.put("workload_id", workloadId);
            report.put("workload_version", WorkloadContract.versionFor(workloadId));
            report.put("workload", compatibilityWorkloadName());
            report.put("metric_limitations", WorkloadContract.limitationFor(workloadId));
            report.put("workload_config", workloadConfig());
            if (WorkloadContract.TRANSFER_ID.equals(workloadId)) {
                report.put("warmup_seconds", warmupSeconds);
                report.put("measure_seconds", measureSeconds);
            }
            report.put("host_device", DeviceSnapshot.capture(activity));
            report.put("candidate", candidate == null ? JSONObject.NULL : candidate.toJson());
            report.put("phases", phaseResults);

            JSONArray failureCatalog = FailureCatalog.fromPhases(phaseResults);
            JSONObject summary;
            JSONObject capabilityDiff = capabilityDiff();
            JSONObject renderCorrectness = null;
            JSONObject statisticalAnalysis = null;
            JSONObject traceReplay = null;
            String verdict;
            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
                renderCorrectness = analyzeRenderCorrectness(failureCatalog);
                summary = correctionSummary(renderCorrectness, failureCatalog);
                verdict = correctionVerdict(renderCorrectness, failureCatalog);
            } else {
                summary = (WorkloadContract.isPhase2(workloadId)
                        || WorkloadContract.TRACE_REPLAY_ID.equals(workloadId))
                        ? Phase2Metrics.summarize(phaseResults, workloadId)
                        : summarizeTransfer();
                statisticalAnalysis = StatisticalComparison.analyze(
                        phaseResults, workloadId);
                if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
                    traceReplay = TraceReplayAnalysis.analyze(phaseResults, rounds, mode);
                    appendTraceFailures(failureCatalog, traceReplay);
                    verdict = TraceReplayAnalysis.verdictFor(
                            traceReplay, statisticalAnalysis, mode);
                } else {
                    verdict = mode == MODE_AB
                            ? StatisticalComparison.verdictFor(statisticalAnalysis,
                                    summary.optInt("failed_phases", 0))
                            : (summary.optInt("failed_phases", 0) > 0
                                    ? "failed_execution" : "completed_single_driver_measurement");
                }
            }
            report.put("summary", summary);
            report.put("analysis_contract", StatisticalComparison.contractJson());
            report.put("statistical_analysis",
                    statisticalAnalysis == null ? JSONObject.NULL : statisticalAnalysis);
            report.put("render_correctness",
                    renderCorrectness == null ? JSONObject.NULL : renderCorrectness);
            report.put("trace_contract", WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)
                    ? TraceReplayContract.contractJson(traceId) : JSONObject.NULL);
            report.put("trace_replay",
                    traceReplay == null ? JSONObject.NULL : traceReplay);
            report.put("capability_diff",
                    capabilityDiff == null ? JSONObject.NULL : capabilityDiff);
            report.put("failure_catalog", failureCatalog);
            report.put("verdict", verdict);
            report.put("validity_warnings", buildWarnings(
                    renderCorrectness, failureCatalog, statisticalAnalysis, traceReplay));
            report.put("phase4_contract", Phase4Contract.contractJson());
            report.put("phase6_contract", executionContext == null
                    ? JSONObject.NULL : Phase6Contract.contractJson());
            report.put("campaign_context", executionContext == null
                    ? JSONObject.NULL : executionContext);
            report.put("hardware_identity", HardwareIdentity.fromReport(report));

            File reportFile = new File(suiteDirectory, "suite.json");
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            listener.onComplete(reportFile, report);
        } catch (Throwable error) {
            listener.onFailure("Falha ao consolidar a suíte", error);
        }
    }

    private JSONObject workloadConfig() throws Exception {
        JSONObject config = new JSONObject();
        if (WorkloadContract.TRANSFER_ID.equals(workloadId)) {
            config.put("warmup_seconds", warmupSeconds);
            config.put("measure_seconds", measureSeconds);
        } else if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
            config.put("image_width", WorkloadContract.RENDER_WIDTH);
            config.put("image_height", WorkloadContract.RENDER_HEIGHT);
            config.put("pixel_tolerance", pixelTolerance);
            config.put("block_size_px", WorkloadContract.BLOCK_SIZE);
            config.put("minimum_block_match_percent",
                    WorkloadContract.MINIMUM_BLOCK_MATCH_PERCENT);
            config.put("maximum_divergent_blocks", maximumDivergentBlocks);
        } else if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
            config.put("warmup_seconds", warmupSeconds);
            config.put("measure_seconds", measureSeconds);
            config.put("primary_metric", WorkloadContract.TRACE_REPLAY_METRIC);
            config.put("trace", TraceReplayContract.definition(traceId));
        } else {
            config.put("warmup_seconds", warmupSeconds);
            config.put("measure_seconds", measureSeconds);
            config.put("primary_metric", WorkloadContract.primaryMetricFor(workloadId));
        }
        return config;
    }

    private String compatibilityWorkloadName() {
        return WorkloadContract.nativeNameFor(workloadId);
    }

    private JSONObject summarizeTransfer() throws Exception {
        List<Double> system = new ArrayList<>();
        List<Double> candidateValues = new ArrayList<>();
        int failures = 0;
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.getJSONObject(index);
            if (!phase.optBoolean("success", false)) {
                failures++;
                continue;
            }
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult == null || !nativeResult.optBoolean("success", false)) {
                failures++;
                continue;
            }
            double value = nativeResult.optDouble(WorkloadContract.TRANSFER_METRIC, Double.NaN);
            if (!Double.isFinite(value)) continue;
            if ("custom".equals(phase.optString("driver_mode"))) {
                candidateValues.add(value);
            } else {
                system.add(value);
            }
        }

        JSONObject summary = new JSONObject();
        summary.put("system", transferStatistics(system));
        summary.put("candidate", transferStatistics(candidateValues));
        summary.put("failed_phases", failures);
        if (!system.isEmpty() && !candidateValues.isEmpty()) {
            double systemMedian = median(system);
            double candidateMedian = median(candidateValues);
            summary.put("candidate_vs_system_percent",
                    (candidateMedian / systemMedian - 1.0) * 100.0);
        } else {
            summary.put("candidate_vs_system_percent", JSONObject.NULL);
        }
        summary.put("metric_note", WorkloadContract.TRANSFER_LIMITATION);
        return summary;
    }

    private JSONObject transferStatistics(List<Double> values) throws Exception {
        JSONObject stats = new JSONObject();
        stats.put("sample_count", values.size());
        if (values.isEmpty()) {
            stats.put("median_transfer_payload_gib_s", JSONObject.NULL);
            stats.put("mean_transfer_payload_gib_s", JSONObject.NULL);
            stats.put("coefficient_of_variation_percent", JSONObject.NULL);
            return stats;
        }
        double sum = 0.0;
        for (double value : values) sum += value;
        double mean = sum / values.size();
        double variance = 0.0;
        for (double value : values) variance += (value - mean) * (value - mean);
        variance /= values.size();
        stats.put("median_transfer_payload_gib_s", median(values));
        stats.put("mean_transfer_payload_gib_s", mean);
        stats.put("coefficient_of_variation_percent",
                mean == 0.0 ? JSONObject.NULL : Math.sqrt(variance) / mean * 100.0);
        return stats;
    }

    private JSONObject analyzeRenderCorrectness(JSONArray failureCatalog) throws Exception {
        JSONArray comparisons = new JSONArray();
        Set<String> systemHashes = new HashSet<>();
        Set<String> candidateHashes = new HashSet<>();
        double minimumPixelMatch = Double.POSITIVE_INFINITY;
        int maximumDivergent = 0;
        boolean allPassed = true;

        for (int round = 1; round <= rounds; ++round) {
            JSONObject system = findSuccessfulPhase(round, false);
            JSONObject candidatePhase = findSuccessfulPhase(round, true);
            collectHash(system, systemHashes);
            collectHash(candidatePhase, candidateHashes);
            if (system == null || candidatePhase == null) continue;

            int[] systemPixels = loadEvidencePixels(system);
            int[] candidatePixels = loadEvidencePixels(candidatePhase);
            RenderComparator.Result comparison = RenderComparator.compare(
                    systemPixels, candidatePixels,
                    WorkloadContract.RENDER_WIDTH, WorkloadContract.RENDER_HEIGHT,
                    pixelTolerance, WorkloadContract.BLOCK_SIZE,
                    WorkloadContract.MINIMUM_BLOCK_MATCH_PERCENT);
            JSONObject comparisonJson = comparison.toJson(maximumDivergentBlocks);
            comparisonJson.put("round", round);
            comparisonJson.put("system_sha256_rgba", evidenceHash(system));
            comparisonJson.put("candidate_sha256_rgba", evidenceHash(candidatePhase));
            comparisons.put(comparisonJson);
            minimumPixelMatch = Math.min(minimumPixelMatch, comparison.pixelMatchPercent());
            maximumDivergent = Math.max(maximumDivergent, comparison.divergentBlocks.size());
            if (!comparison.passes(maximumDivergentBlocks)) {
                allPassed = false;
                FailureCatalog.appendRenderMismatch(failureCatalog, round, comparisonJson);
            }
        }

        // Single-driver modes still record deterministic hashes across their own rounds.
        if (mode != MODE_AB) {
            for (int index = 0; index < phaseResults.length(); ++index) {
                JSONObject phase = phaseResults.optJSONObject(index);
                if (phase == null || !phase.optBoolean("success", false)) continue;
                collectHash(phase, "custom".equals(phase.optString("driver_mode"))
                        ? candidateHashes : systemHashes);
            }
        }

        JSONObject result = new JSONObject();
        result.put("comparison_available", comparisons.length() > 0);
        result.put("comparisons", comparisons);
        result.put("comparison_count", comparisons.length());
        result.put("pixel_match_percent", comparisons.length() == 0
                ? JSONObject.NULL : minimumPixelMatch);
        result.put("maximum_divergent_block_count", comparisons.length() == 0
                ? JSONObject.NULL : maximumDivergent);
        result.put("passed", comparisons.length() > 0 && allPassed);
        result.put("system_unique_render_hashes", stringArray(systemHashes));
        result.put("candidate_unique_render_hashes", stringArray(candidateHashes));
        result.put("system_nondeterministic", systemHashes.size() > 1);
        result.put("candidate_nondeterministic", candidateHashes.size() > 1);
        result.put("metric_note", WorkloadContract.RENDER_CORRECTNESS_LIMITATION);
        return result;
    }

    private JSONObject correctionSummary(JSONObject correction, JSONArray failures) throws Exception {
        JSONObject summary = new JSONObject();
        summary.put("failed_events", failures.length());
        summary.put("comparison_count", correction.optInt("comparison_count", 0));
        summary.put("pixel_match_percent",
                correction.has("pixel_match_percent")
                        ? correction.opt("pixel_match_percent") : JSONObject.NULL);
        summary.put("maximum_divergent_block_count",
                correction.has("maximum_divergent_block_count")
                        ? correction.opt("maximum_divergent_block_count") : JSONObject.NULL);
        summary.put("render_correctness_passed", correction.optBoolean("passed", false));
        summary.put("metric_note", WorkloadContract.RENDER_CORRECTNESS_LIMITATION);
        return summary;
    }

    private String correctionVerdict(JSONObject correction, JSONArray failures) {
        for (int index = 0; index < failures.length(); ++index) {
            JSONObject failure = failures.optJSONObject(index);
            if (failure != null && !"render_mismatch".equals(
                    failure.optString("failure_type"))) {
                return "failed_execution";
            }
        }
        if (!correction.optBoolean("comparison_available", false)) {
            return "completed_no_reference";
        }
        return correction.optBoolean("passed", false)
                ? "passed_render_correctness" : "failed_render_correctness";
    }

    private JSONObject capabilityDiff() throws Exception {
        JSONObject systemCapabilities = firstCapabilities(false);
        JSONObject candidateCapabilities = firstCapabilities(true);
        if (systemCapabilities == null || candidateCapabilities == null) return null;
        return CapabilityDiff.compare(systemCapabilities, candidateCapabilities);
    }

    private JSONObject firstCapabilities(boolean custom) {
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            if (custom != "custom".equals(phase.optString("driver_mode"))) continue;
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult == null) continue;
            JSONObject capabilities = nativeResult.optJSONObject("capabilities");
            if (capabilities != null) return capabilities;
        }
        return null;
    }

    private JSONObject findSuccessfulPhase(int round, boolean custom) {
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            if (phase.optInt("round", -1) != round) continue;
            if (custom == "custom".equals(phase.optString("driver_mode"))) return phase;
        }
        return null;
    }

    private int[] loadEvidencePixels(JSONObject phase) throws Exception {
        JSONObject evidence = phase.optJSONObject("evidence");
        if (evidence == null) throw new IllegalStateException("Fase sem preview de render");
        String relativePath = evidence.optString("relative_path", "");
        File file = new File(suiteDirectory, relativePath);
        if (relativePath.isEmpty() || !ResultFiles.isInside(suiteDirectory, file) || !file.isFile()) {
            throw new IllegalStateException("Preview de render ausente ou fora da suíte");
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) throw new IllegalStateException("PNG de evidência inválido");
        try {
            if (bitmap.getWidth() != WorkloadContract.RENDER_WIDTH
                    || bitmap.getHeight() != WorkloadContract.RENDER_HEIGHT) {
                throw new IllegalStateException("PNG com dimensões inesperadas");
            }
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                    bitmap.getWidth(), bitmap.getHeight());
            return pixels;
        } finally {
            bitmap.recycle();
        }
    }

    private static void collectHash(JSONObject phase, Set<String> destination) {
        if (phase == null) return;
        String hash = evidenceHash(phase);
        if (!hash.isEmpty()) destination.add(hash);
    }

    private static String evidenceHash(JSONObject phase) {
        JSONObject evidence = phase == null ? null : phase.optJSONObject("evidence");
        return evidence == null ? "" : evidence.optString("sha256_rgba", "");
    }

    private static JSONArray stringArray(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        JSONArray array = new JSONArray();
        for (String value : sorted) array.put(value);
        return array;
    }

    private static double median(List<Double> input) {
        List<Double> values = new ArrayList<>(input);
        Collections.sort(values);
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0
                : values.get(middle);
    }

    private void appendTraceFailures(JSONArray failures, JSONObject traceReplay) throws Exception {
        JSONArray comparisons = traceReplay.optJSONArray("comparisons");
        if (comparisons != null) {
            for (int index = 0; index < comparisons.length(); ++index) {
                JSONObject comparison = comparisons.optJSONObject(index);
                if (comparison == null || comparison.optBoolean("match", true)) continue;
                failures.put(new JSONObject()
                        .put("phase", "paired")
                        .put("driver_mode", "system_vs_custom")
                        .put("round", comparison.optInt("round", -1))
                        .put("failure_type", "trace_output_mismatch")
                        .put("failure_stage", "trace_correctness_gate")
                        .put("system_sha256", comparison.optString("system_sha256"))
                        .put("candidate_sha256", comparison.optString("candidate_sha256"))
                        .put("message", "Saída binária do trace divergiu no par A/B."));
            }
        }
        if (traceReplay.optBoolean("system_nondeterministic", false)) {
            failures.put(new JSONObject()
                    .put("phase", "system")
                    .put("driver_mode", "system")
                    .put("failure_type", "trace_nondeterminism")
                    .put("failure_stage", "trace_correctness_gate")
                    .put("message", "O braço do sistema produziu hashes diferentes entre rodadas."));
        }
        if (traceReplay.optBoolean("candidate_nondeterministic", false)) {
            failures.put(new JSONObject()
                    .put("phase", "candidate")
                    .put("driver_mode", "custom")
                    .put("failure_type", "trace_nondeterminism")
                    .put("failure_stage", "trace_correctness_gate")
                    .put("message", "O candidato produziu hashes diferentes entre rodadas."));
        }
    }

    private JSONArray buildWarnings(JSONObject correction, JSONArray failureCatalog,
                                      JSONObject statisticalAnalysis, JSONObject traceReplay) throws Exception {
        JSONArray warnings = new JSONArray();
        double minimumTemperature = Double.POSITIVE_INFINITY;
        double maximumTemperature = Double.NEGATIVE_INFINITY;
        boolean timestampFallback = false;
        boolean validationFailure = false;
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.getJSONObject(index);
            if (!phase.optBoolean("success", false)) {
                warnings.put("Uma ou mais fases falharam, encerraram ou expiraram.");
            }
            if (WorkloadContract.TRANSFER_ID.equals(workloadId)) {
                JSONObject nativeResult = phase.optJSONObject("native");
                if (nativeResult != null && !nativeResult.optBoolean("gpu_timestamps_used", false)) {
                    timestampFallback = true;
                }
            }
            JSONObject before = phase.optJSONObject("device_before");
            if (before != null) {
                double temperature = before.optDouble("battery_temperature_c", Double.NaN);
                if (Double.isFinite(temperature)) {
                    minimumTemperature = Math.min(minimumTemperature, temperature);
                    maximumTemperature = Math.max(maximumTemperature, temperature);
                }
            }
        }
        for (int index = 0; index < failureCatalog.length(); ++index) {
            JSONObject failure = failureCatalog.optJSONObject(index);
            if (failure != null && "validation_error".equals(
                    failure.optString("failure_type"))) validationFailure = true;
        }
        if (timestampFallback) {
            warnings.put("A GPU não forneceu timestamps válidos; a fase usou relógio de parede.");
        }
        if (validationFailure) {
            warnings.put("Foram capturadas mensagens de erro da camada de validação.");
        }
        if (Double.isFinite(minimumTemperature)
                && maximumTemperature - minimumTemperature > 2.0) {
            warnings.put(String.format(Locale.US,
                    "Temperatura inicial variou %.1f °C entre fases; repita após resfriamento.",
                    maximumTemperature - minimumTemperature));
        }
        if (rounds < 3) {
            warnings.put("Menos de três rodadas: resultado exploratório, não conclusivo.");
        }
        if (traceReplay != null) {
            if (traceReplay.optBoolean("system_nondeterministic", false)
                    || traceReplay.optBoolean("candidate_nondeterministic", false)) {
                warnings.put("Trace não determinístico: hashes variaram entre rodadas do mesmo braço.");
            }
            if (traceReplay.optInt("output_mismatch_count", 0) > 0) {
                warnings.put("Saída do trace divergiu entre sistema e candidato; performance bloqueada.");
            }
            if (mode == MODE_AB && traceReplay.optInt("complete_pair_count", 0) < rounds) {
                warnings.put("Trace replay sem todos os pares A/B completos.");
            }
        }
        if (statisticalAnalysis != null) {
            int paired = statisticalAnalysis.optInt("paired_sample_count", 0);
            if (paired < WorkloadContract.MINIMUM_PAIRED_SAMPLES) {
                warnings.put("Menos de cinco pares A/B válidos: inferência inconclusiva.");
            }
            JSONObject orderBias = statisticalAnalysis.optJSONObject("order_bias_diagnostic");
            double orderDifference = orderBias == null ? Double.NaN
                    : orderBias.optDouble("median_difference_percent_points", Double.NaN);
            if (Double.isFinite(orderDifference) && Math.abs(orderDifference)
                    > WorkloadContract.PRACTICAL_EQUIVALENCE_MARGIN_PERCENT) {
                warnings.put(String.format(Locale.US,
                        "Possível viés de ordem AB/BA: diferença mediana de %+.2f p.p.",
                        orderDifference));
            }
            if ("inconclusive".equals(statisticalAnalysis.optString("classification"))) {
                warnings.put("O IC95% cruza a margem prática; não declare ganho ou regressão.");
            }
        }
        if (correction != null) {
            if (correction.optBoolean("system_nondeterministic", false)) {
                warnings.put("O driver do sistema produziu hashes exatos diferentes entre rodadas.");
            }
            if (correction.optBoolean("candidate_nondeterministic", false)) {
                warnings.put("O candidato produziu hashes exatos diferentes entre rodadas.");
            }
            if (!correction.optBoolean("comparison_available", false)) {
                warnings.put("Sem braço sistema × candidato; a correção relativa não pôde ser julgada.");
            }
        }
        return deduplicate(warnings);
    }

    private static JSONArray deduplicate(JSONArray input) {
        Set<String> seen = new HashSet<>();
        JSONArray output = new JSONArray();
        for (int index = 0; index < input.length(); ++index) {
            String value = input.optString(index, "");
            if (!value.isEmpty() && seen.add(value)) output.put(value);
        }
        return output;
    }

    private String modeName() {
        if (mode == MODE_SYSTEM) return "system_only";
        if (mode == MODE_CUSTOM) return "candidate_only";
        return "ab_system_vs_candidate";
    }
}
