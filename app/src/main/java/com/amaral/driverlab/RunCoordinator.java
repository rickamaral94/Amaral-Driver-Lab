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
        final boolean candidateArm;
        final int round;
        final String label;
        final DriverPackage driver;

        Phase(boolean candidateArm, int round, DriverPackage driver) {
            this.candidateArm = candidateArm;
            this.round = round;
            // Keep the historical analytical baseline label for score compatibility.
            this.label = candidateArm ? "candidate" : "system";
            this.driver = driver;
        }

        boolean usesCustomDriver() {
            return driver != null;
        }

        String executionRole() {
            return DriverExecutionIdentity.role(candidateArm, usesCustomDriver());
        }
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DriverPackage candidate;
    private final DriverPackage reference;
    private final int mode;
    private int rounds;
    private final int warmupSeconds;
    private final int measureSeconds;
    private final String workloadId;
    private final int workloadVersion;
    private final String traceId;
    private final int pixelTolerance;
    private final int maximumDivergentBlocks;
    private final Listener listener;
    private final JSONObject campaignContext;
    private final JSONObject qualificationContext;
    private final List<Phase> phases = new ArrayList<>();
    private final JSONArray phaseResults = new JSONArray();
    private final JSONArray calibrationObservations = new JSONArray();
    private final JSONArray sampleSizePilotResults = new JSONArray();
    private final JSONArray effectInjectionObservations = new JSONArray();

    private File suiteDirectory;
    private long suiteStartedAt;
    private int phaseIndex;
    private long phaseDeadlineElapsed;
    private long phaseLaunchedElapsed;
    private File currentResultFile;
    private boolean calibrationProbeActive;
    private boolean sampleSizePilotActive;
    private boolean finalPlanBuilt;
    private boolean postCalibrationValidationComplete;
    private String calibrationStage = "none";
    private int calibrationRepetitions = 1;
    private int calibrationEffectPercent;
    private double calibratedMedianUs = Double.NaN;
    private double doubledMedianUs = Double.NaN;
    private double postValidationMedianUs = Double.NaN;
    private JSONObject calibrationKey;
    private JSONObject calibrationDevice;
    private String calibrationThermalProfile;
    private JSONObject linearity;
    private JSONObject sampleSizePlan;
    private JSONObject calibrationFailure;
    private boolean operationalBudgetExhausted;

    RunCoordinator(Activity activity, DriverPackage candidate, int mode, int rounds,
                   int warmupSeconds, int measureSeconds, String workloadId, String traceId,
                   int pixelTolerance, int maximumDivergentBlocks, Listener listener) {
        this(activity, candidate, null, mode, rounds, warmupSeconds, measureSeconds, workloadId,
                traceId, pixelTolerance, maximumDivergentBlocks, null, null, listener);
    }

    RunCoordinator(Activity activity, DriverPackage candidate, int mode, int rounds,
                   int warmupSeconds, int measureSeconds, String workloadId, String traceId,
                   int pixelTolerance, int maximumDivergentBlocks, JSONObject campaignContext,
                   Listener listener) {
        this(activity, candidate, null, mode, rounds, warmupSeconds, measureSeconds, workloadId,
                traceId, pixelTolerance, maximumDivergentBlocks, campaignContext, null, listener);
    }

    RunCoordinator(Activity activity, DriverPackage candidate, int mode, int rounds,
                   int warmupSeconds, int measureSeconds, String workloadId, String traceId,
                   int pixelTolerance, int maximumDivergentBlocks, JSONObject campaignContext,
                   JSONObject qualificationContext, Listener listener) {
        this(activity, candidate, null, mode, rounds, warmupSeconds, measureSeconds, workloadId,
                traceId, pixelTolerance, maximumDivergentBlocks, campaignContext,
                qualificationContext, listener);
    }

    RunCoordinator(Activity activity, DriverPackage candidate, DriverPackage reference, int mode,
                   int rounds, int warmupSeconds, int measureSeconds, String workloadId,
                   String traceId, int pixelTolerance, int maximumDivergentBlocks,
                   JSONObject campaignContext, JSONObject qualificationContext,
                   Listener listener) {
        this(activity, candidate, reference, mode, rounds, warmupSeconds, measureSeconds,
                workloadId, WorkloadContract.versionFor(workloadId), traceId, pixelTolerance,
                maximumDivergentBlocks, campaignContext, qualificationContext, listener);
    }

    RunCoordinator(Activity activity, DriverPackage candidate, DriverPackage reference, int mode,
                   int rounds, int warmupSeconds, int measureSeconds, String workloadId,
                   int workloadVersion, String traceId, int pixelTolerance,
                   int maximumDivergentBlocks, JSONObject campaignContext,
                   JSONObject qualificationContext, Listener listener) {
        this.activity = activity;
        this.candidate = candidate;
        this.reference = reference;
        this.mode = mode;
        this.rounds = Math.max(1, Math.min(rounds,
                BenchmarkCalibrationContract.MAXIMUM_FINAL_PAIRED_ROUNDS));
        this.warmupSeconds = Math.max(0, Math.min(warmupSeconds, 30));
        this.measureSeconds = Math.max(1, Math.min(measureSeconds, 120));
        if (!WorkloadContract.isSupported(workloadId)) {
            throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
        }
        this.workloadId = workloadId;
        if (!WorkloadContract.isSupportedVersion(workloadId, workloadVersion)) {
            throw new IllegalArgumentException("Versão de workload incompatível: "
                    + workloadId + "/v" + workloadVersion);
        }
        this.workloadVersion = workloadVersion;
        this.traceId = WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)
                ? (TraceReplayContract.isSupported(traceId) ? traceId
                : TraceReplayContract.MIXED_TRACE_ID) : TraceReplayContract.MIXED_TRACE_ID;
        this.pixelTolerance = Math.max(0, Math.min(pixelTolerance, 255));
        this.maximumDivergentBlocks = Math.max(0, maximumDivergentBlocks);
        this.campaignContext = copyContext(campaignContext, "campaign_context");
        this.qualificationContext = copyContext(qualificationContext, "qualification_context");
        this.listener = listener;
    }

    private static JSONObject copyContext(JSONObject source, String label) {
        if (source == null) return null;
        try {
            return new JSONObject(source.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException(label + " inválido", error);
        }
    }

    void start() {
        try {
            if ((mode == MODE_CUSTOM || mode == MODE_AB)
                    && (candidate == null || !candidate.isUsable())) {
                throw new IllegalStateException("Importe um driver válido para este modo");
            }
            if (reference != null && !reference.isUsable()) {
                throw new IllegalStateException("Driver de referência inválido");
            }
            if (reference != null && candidate != null
                    && reference.sha256.equalsIgnoreCase(candidate.sha256)) {
                throw new IllegalStateException("Candidato e referência devem ser diferentes");
            }
            suiteStartedAt = System.currentTimeMillis();
            suiteDirectory = new File(new File(activity.getFilesDir(), "runs"),
                    "suite-" + suiteStartedAt);
            if (!suiteDirectory.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar a pasta da suíte");
            }
            if (BenchmarkCalibrationContract.requiresCalibration(
                    workloadId, workloadVersion) && mode == MODE_AB) {
                startCalibration();
            } else {
                finalPlanBuilt = true;
                buildPlan();
                launchNext();
            }
        } catch (Throwable error) {
            listener.onFailure("Não foi possível iniciar a suíte", error);
        }
    }

    private void startCalibration() throws Exception {
        calibrationDevice = DeviceSnapshot.capture(activity);
        calibrationThermalProfile = thermalProfile(calibrationDevice);
        // Vulkan is initialized in the isolated runner, so one unscaled identity probe is
        // required before an exact hardware_key can be constructed. A cache hit reuses m;
        // this probe never changes the persisted multiplier.
        calibrationStage = "identify_hardware";
        launchCalibrationProbe(1, calibrationStage);
    }

    private void buildPlan() {
        phases.clear();
        if (mode == MODE_SYSTEM) {
            for (int round = 1; round <= rounds; ++round) {
                phases.add(new Phase(false, round, reference));
            }
            return;
        }
        if (mode == MODE_CUSTOM) {
            for (int round = 1; round <= rounds; ++round) {
                phases.add(new Phase(true, round, candidate));
            }
            return;
        }
        for (int round = 1; round <= rounds; ++round) {
            // Alternating AB/BA reduces bias from temperature drift and run order.
            boolean candidateFirst = round % 2 == 0;
            phases.add(candidateFirst
                    ? new Phase(true, round, candidate)
                    : new Phase(false, round, reference));
            phases.add(candidateFirst
                    ? new Phase(false, round, reference)
                    : new Phase(true, round, candidate));
        }
    }

    private void launchCalibrationProbe(int repetitions, String stage) {
        launchCalibrationProbe(repetitions, 0, stage);
    }

    private void launchCalibrationProbe(int repetitions, int effectPercent, String stage) {
        try {
            Phase phase = new Phase(false, 0, reference);
            currentResultFile = new File(suiteDirectory,
                    "calibration-" + stage.replaceAll("[^a-z0-9_-]", "-") + ".json");
            listener.onStatus("Calibração " + stage + " · " + repetitions
                    + " repetição(ões) · injeção GPU " + effectPercent
                    + "% · braço de referência");
            Intent intent = new Intent(activity, VisualSceneContract.isVisualScene(workloadId)
                    ? VisualRunnerActivity.class : RunnerActivity.class);
            intent.putExtra(RunnerActivity.EXTRA_RESULT_PATH,
                    currentResultFile.getAbsolutePath());
            intent.putExtra(RunnerActivity.EXTRA_PHASE_LABEL, "calibration_reference");
            intent.putExtra(RunnerActivity.EXTRA_ROUND, 0);
            intent.putExtra(RunnerActivity.EXTRA_WARMUP_SECONDS, Math.max(1, warmupSeconds));
            intent.putExtra(RunnerActivity.EXTRA_MEASURE_SECONDS, Math.max(2, measureSeconds));
            intent.putExtra(RunnerActivity.EXTRA_WORKLOAD_ID, workloadId);
            intent.putExtra(RunnerActivity.EXTRA_TRACE_ID, traceId);
            intent.putExtra(RunnerActivity.EXTRA_WORKLOAD_VERSION, workloadVersion);
            intent.putExtra(RunnerActivity.EXTRA_REPETITIONS_PER_SAMPLE, repetitions);
            intent.putExtra(RunnerActivity.EXTRA_EFFECT_INJECTION_PERCENT, effectPercent);
            intent.putExtra(RunnerActivity.EXTRA_PIXEL_TOLERANCE, pixelTolerance);
            intent.putExtra(RunnerActivity.EXTRA_MAX_DIVERGENT_BLOCKS,
                    maximumDivergentBlocks);
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_MODE_OVERRIDE,
                    DriverExecutionIdentity.mode(phase.usesCustomDriver()));
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_ROLE, phase.executionRole());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_DISPLAY_NAME,
                    phase.driver == null ? "" : phase.driver.displayName());
            if (phase.driver != null) {
                intent.putExtra(RunnerActivity.EXTRA_DRIVER_DIR,
                        phase.driver.directory.getAbsolutePath());
                intent.putExtra(RunnerActivity.EXTRA_DRIVER_NAME, phase.driver.libraryName);
                intent.putExtra(RunnerActivity.EXTRA_DRIVER_META,
                        phase.driver.metadata.toString());
                intent.putExtra(RunnerActivity.EXTRA_DRIVER_SHA, phase.driver.sha256);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            calibrationProbeActive = true;
            calibrationEffectPercent = effectPercent;
            phaseLaunchedElapsed = SystemClock.elapsedRealtime();
            phaseDeadlineElapsed = phaseLaunchedElapsed + WorkloadContract.timeoutSeconds(
                    workloadId, Math.max(1, warmupSeconds), Math.max(2, measureSeconds)) * 1000L;
            activity.startActivity(intent);
            handler.postDelayed(this::pollCurrent, 500);
        } catch (Throwable error) {
            calibrationProbeActive = false;
            listener.onFailure("Não foi possível iniciar a calibração", error);
        }
    }

    private void scheduleCalibrationProbe(int repetitions, String stage) {
        scheduleCalibrationProbe(repetitions, 0, stage);
    }

    private void scheduleCalibrationProbe(int repetitions, int effectPercent, String stage) {
        // RunnerActivity and VisualRunnerActivity share the :runner process. The activity that
        // just wrote the result still has a pending self-termination callback, so starting the
        // next probe immediately lets the old callback kill the new probe in the same process.
        handler.postDelayed(() -> launchCalibrationProbe(repetitions, effectPercent, stage),
                RunnerProcessLifecycle.RELAUNCH_DELAY_MS);
    }

    private void handleCalibrationProbe(JSONObject result) throws Exception {
        double medianUs = extractMedianBatchUs(result);
        if (!result.optBoolean("success", false) || !Double.isFinite(medianUs)
                || medianUs <= 0.0) {
            throw new IllegalStateException("Probe de calibração sem mediana válida");
        }
        JSONObject nativeResult = result.optJSONObject("native");
        calibrationObservations.put(new JSONObject()
                .put("stage", calibrationStage)
                .put("repetitions_per_sample", nativeResult == null ? 1
                        : nativeResult.optInt("repetitions_per_sample", 1))
                .put("effect_injection_percent", calibrationEffectPercent)
                .put("median_batch_us", medianUs)
                .put("runtime_driver_identity", result.opt("runtime_driver_identity")));
        if ("identify_hardware".equals(calibrationStage)) {
            JSONObject capabilities = nativeResult == null ? null
                    : nativeResult.optJSONObject("capabilities");
            String gpuName = capabilities == null ? "unknown"
                    : capabilities.optString("gpu_name", "unknown");
            calibrationKey = BenchmarkCalibrationStore.key(
                    calibrationDevice, gpuName, workloadId, workloadVersion, workloadConfig(),
                    timestampSource(nativeResult),
                    calibrationThermalProfile);
            JSONObject cached = BenchmarkCalibrationStore.find(
                    activity.getFilesDir(), calibrationKey);
            if (cached != null && cached.optInt("repetitions_per_sample", 0) > 0) {
                calibrationRepetitions = cached.getInt("repetitions_per_sample");
                calibrationStage = "validate_cached_m";
            } else {
                calibrationRepetitions = BenchmarkCalibrationContract.selectMultiplier(medianUs);
                calibrationStage = "calibrated_m";
            }
            scheduleCalibrationProbe(calibrationRepetitions, calibrationStage);
            return;
        }
        if ("pilot_base".equals(calibrationStage)) {
            calibrationRepetitions = BenchmarkCalibrationContract.selectMultiplier(medianUs);
            calibrationStage = "calibrated_m";
            scheduleCalibrationProbe(calibrationRepetitions, calibrationStage);
            return;
        }
        if ("validate_cached_m".equals(calibrationStage)
                || "calibrated_m".equals(calibrationStage)) {
            calibratedMedianUs = medianUs;
            calibrationStage = "linearity_2m";
            scheduleCalibrationProbe(Math.min(BenchmarkCalibrationContract.MAX_REPETITIONS,
                    calibrationRepetitions * 2), calibrationStage);
            return;
        }
        if ("linearity_2m".equals(calibrationStage)) {
            doubledMedianUs = medianUs;
            linearity = BenchmarkCalibrationContract.linearity(
                    calibratedMedianUs, doubledMedianUs);
            if ("linear".equals(linearity.optString("status"))
                    && calibratedMedianUs >= BenchmarkCalibrationContract.VALIDITY_FLOOR_US) {
                BenchmarkCalibrationStore.put(activity.getFilesDir(), calibrationKey,
                        calibrationRepetitions, calibratedMedianUs, linearity);
            }
            calibrationStage = "effect_injection_1";
            scheduleCalibrationProbe(calibrationRepetitions, 1, calibrationStage);
            return;
        }
        if (calibrationStage.startsWith("effect_injection_")) {
            int nominal = calibrationEffectPercent;
            double realized = nativeResult == null ? Double.NaN
                    : nativeResult.optDouble(
                            "realized_workload_effect_percent", Double.NaN);
            String domain = nativeResult == null ? "unknown"
                    : nativeResult.optString("effect_injection_domain", "unknown");
            effectInjectionObservations.put(
                    BenchmarkCalibrationContract.effectInjectionObservation(
                            nominal, realized, calibratedMedianUs, medianUs, domain));
            if (nominal == 1) {
                calibrationStage = "effect_injection_3";
                scheduleCalibrationProbe(calibrationRepetitions, 3, calibrationStage);
            } else if (nominal == 3) {
                calibrationStage = "effect_injection_10";
                scheduleCalibrationProbe(calibrationRepetitions, 10, calibrationStage);
            } else {
                handler.postDelayed(this::startSampleSizePilot,
                        RunnerProcessLifecycle.RELAUNCH_DELAY_MS);
            }
            return;
        }
        if ("post_run_reference_validation".equals(calibrationStage)) {
            postValidationMedianUs = medianUs;
            postCalibrationValidationComplete = true;
            finishSuite();
            return;
        }
        throw new IllegalStateException("Estado de calibração desconhecido: " + calibrationStage);
    }

    private static String thermalProfile(JSONObject device) {
        int status = device == null ? -1 : device.optInt("thermal_status", -1);
        double temperature = device == null ? Double.NaN
                : device.optDouble("battery_temperature_c", Double.NaN);
        String bucket = !Double.isFinite(temperature) ? "unknown"
                : temperature < 30.0 ? "cold"
                : temperature < 38.0 ? "nominal"
                : temperature < 43.0 ? "warm" : "hot";
        return "android_status_" + status + "/battery_" + bucket;
    }

    private String timestampSource(JSONObject nativeResult) {
        if (WorkloadContract.SHADER_COMPILE_ID.equals(workloadId)) {
            return "cpu_monotonic_pipeline_creation";
        }
        return nativeResult != null && nativeResult.optBoolean("gpu_timestamps_used", false)
                ? "gpu_timestamp_query" : "cpu_monotonic_submission_fallback";
    }

    private void startSampleSizePilot() {
        sampleSizePilotActive = true;
        finalPlanBuilt = false;
        rounds = BenchmarkCalibrationContract.PILOT_PAIRED_ROUNDS;
        phaseIndex = 0;
        buildPlan();
        listener.onStatus("Piloto pareado independente · " + rounds
                + " pares; amostras não entram na estimativa final");
        launchNext();
    }

    private void finishSampleSizePilot() {
        try {
            JSONArray ratios = new JSONArray();
            for (int round = 1; round <= BenchmarkCalibrationContract.PILOT_PAIRED_ROUNDS;
                 ++round) {
                JSONObject referencePhase = findSuccessfulPhase(
                        sampleSizePilotResults, round, false);
                JSONObject candidatePhase = findSuccessfulPhase(
                        sampleSizePilotResults, round, true);
                double referenceValue = extractPrimaryMetric(referencePhase);
                double candidateValue = extractPrimaryMetric(candidatePhase);
                if (Double.isFinite(referenceValue) && Double.isFinite(candidateValue)
                        && referenceValue > 0.0 && candidateValue > 0.0) {
                    ratios.put(candidateValue / referenceValue);
                }
            }
            sampleSizePlan = BenchmarkCalibrationContract.fixedSampleSizePlan(
                    ratios, BenchmarkCalibrationContract.DEFAULT_OPERATIONAL_BUDGET_SECONDS);
            rounds = sampleSizePlan.getInt("planned_paired_rounds");
            sampleSizePilotActive = false;
            finalPlanBuilt = true;
            phaseIndex = 0;
            buildPlan();
            listener.onStatus("Tamanho final fixado após o piloto: " + rounds + " pares");
            launchNext();
        } catch (Throwable error) {
            listener.onFailure("Falha ao fixar o tamanho amostral", error);
        }
    }

    private double extractMedianBatchUs(JSONObject phase) {
        JSONObject nativeResult = phase == null ? null : phase.optJSONObject("native");
        return nativeResult == null ? Double.NaN
                : nativeResult.optDouble("median_batch_us", Double.NaN);
    }

    private double extractPrimaryMetric(JSONObject phase) {
        JSONObject nativeResult = phase == null ? null : phase.optJSONObject("native");
        return nativeResult == null ? Double.NaN : nativeResult.optDouble(
                WorkloadContract.primaryMetricFor(workloadId), Double.NaN);
    }

    private JSONObject findSuccessfulPhase(JSONArray source, int round, boolean candidateArm) {
        for (int index = 0; index < source.length(); ++index) {
            JSONObject phase = source.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)
                    || phase.optInt("round", -1) != round) continue;
            if (candidateArm == DriverExecutionIdentity.isCandidateArm(phase)) return phase;
        }
        return null;
    }

    private void launchNext() {
        if (phaseIndex >= phases.size()) {
            if (sampleSizePilotActive) {
                finishSampleSizePilot();
                return;
            }
            if (finalPlanBuilt
                    && BenchmarkCalibrationContract.requiresCalibration(
                    workloadId, workloadVersion)
                    && !postCalibrationValidationComplete) {
                calibrationStage = "post_run_reference_validation";
                launchCalibrationProbe(calibrationRepetitions, calibrationStage);
                return;
            }
            finishSuite();
            return;
        }
        if (finalPlanBuilt && System.currentTimeMillis() - suiteStartedAt
                >= BenchmarkCalibrationContract.DEFAULT_OPERATIONAL_BUDGET_SECONDS * 1000L) {
            operationalBudgetExhausted = true;
            phases.subList(phaseIndex, phases.size()).clear();
            launchNext();
            return;
        }
        Phase phase = phases.get(phaseIndex);
        String fileName = String.format(Locale.US, "%s-%02d-%s-r%d.json",
                sampleSizePilotActive ? "sample-pilot" : "phase",
                phaseIndex + 1, phase.label, phase.round);
        currentResultFile = new File(suiteDirectory, fileName);
        String workloadLabel = WorkloadContract.labelFor(workloadId, workloadVersion);
        listener.onStatus("Executando " + (phaseIndex + 1) + "/" + phases.size()
                + " · " + workloadLabel + " · rodada " + phase.round + " · "
                + (phase.driver == null ? "driver do sistema" : phase.driver.displayName()));

        Intent intent = new Intent(activity, VisualSceneContract.isVisualScene(workloadId)
                ? VisualRunnerActivity.class : RunnerActivity.class);
        intent.putExtra(RunnerActivity.EXTRA_RESULT_PATH, currentResultFile.getAbsolutePath());
        intent.putExtra(RunnerActivity.EXTRA_PHASE_LABEL, phase.label);
        intent.putExtra(RunnerActivity.EXTRA_ROUND, phase.round);
        intent.putExtra(RunnerActivity.EXTRA_WARMUP_SECONDS, warmupSeconds);
        intent.putExtra(RunnerActivity.EXTRA_MEASURE_SECONDS, measureSeconds);
        intent.putExtra(RunnerActivity.EXTRA_WORKLOAD_ID, workloadId);
        intent.putExtra(RunnerActivity.EXTRA_TRACE_ID, traceId);
        intent.putExtra(RunnerActivity.EXTRA_WORKLOAD_VERSION, workloadVersion);
        intent.putExtra(RunnerActivity.EXTRA_REPETITIONS_PER_SAMPLE,
                workloadVersion >= 2 ? calibrationRepetitions : 1);
        intent.putExtra(RunnerActivity.EXTRA_EFFECT_INJECTION_PERCENT, 0);
        intent.putExtra(RunnerActivity.EXTRA_PIXEL_TOLERANCE, pixelTolerance);
        intent.putExtra(RunnerActivity.EXTRA_MAX_DIVERGENT_BLOCKS, maximumDivergentBlocks);
        intent.putExtra(RunnerActivity.EXTRA_DRIVER_MODE_OVERRIDE,
                DriverExecutionIdentity.mode(phase.usesCustomDriver()));
        intent.putExtra(RunnerActivity.EXTRA_DRIVER_ROLE, phase.executionRole());
        intent.putExtra(RunnerActivity.EXTRA_DRIVER_DISPLAY_NAME,
                phase.driver == null ? "" : phase.driver.displayName());
        if (phase.driver != null) {
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_DIR,
                    phase.driver.directory.getAbsolutePath());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_NAME, phase.driver.libraryName);
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_META, phase.driver.metadata.toString());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_SHA, phase.driver.sha256);
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
                JSONObject completed = new JSONObject(ResultFiles.readUtf8(currentResultFile));
                if (calibrationProbeActive) {
                    if (!completed.optBoolean("success", false)) {
                        finishCalibrationFailure(completed);
                    } else {
                        calibrationProbeActive = false;
                        try {
                            handleCalibrationProbe(completed);
                        } catch (Throwable error) {
                            completed.put("success", false)
                                    .put("failure_type", "calibration_probe_invalid")
                                    .put("failure_stage", "calibration_result_validation")
                                    .put("error", error.toString());
                            ResultFiles.writeAtomic(currentResultFile, completed.toString(2));
                            finishCalibrationFailure(completed);
                        }
                    }
                    return;
                }
                if (sampleSizePilotActive) sampleSizePilotResults.put(completed);
                else phaseResults.put(completed);
                phaseIndex++;
                handler.postDelayed(this::launchNext,
                        RunnerProcessLifecycle.RELAUNCH_DELAY_MS);
                return;
            }
            if (runnerExitedUnexpectedly()) {
                killTimedOutRunner();
                if (calibrationProbeActive) {
                    JSONObject failure = recordSyntheticFailure(
                            "crash", "runner_crash", true);
                    finishCalibrationFailure(failure);
                    return;
                }
                recordSyntheticFailure("crash", "runner_crash", false);
                phaseIndex++;
                handler.postDelayed(this::launchNext,
                        RunnerProcessLifecycle.RELAUNCH_DELAY_MS);
                return;
            }
            if (SystemClock.elapsedRealtime() >= phaseDeadlineElapsed) {
                killTimedOutRunner();
                if (calibrationProbeActive) {
                    JSONObject failure = recordSyntheticFailure(
                            "timeout", "runner_timeout", true);
                    finishCalibrationFailure(failure);
                    return;
                }
                recordSyntheticFailure("timeout", "runner_timeout", false);
                phaseIndex++;
                handler.postDelayed(this::launchNext,
                        RunnerProcessLifecycle.RELAUNCH_DELAY_MS);
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
            JSONObject state = RunnerProcessState.read(currentResultFile);
            if (state == null) return false;
            if (!"started".equals(state.optString("state"))) return false;
            int pid = state.optInt("pid", -1);
            return pid > 0 && !new File("/proc/" + pid).exists();
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject recordSyntheticFailure(String failureType, String error,
                                              boolean calibration) throws Exception {
        Phase phase = calibration
                ? new Phase(false, 0, reference) : phases.get(phaseIndex);
        JSONObject failure = new JSONObject();
        failure.put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION);
        failure.put("success", false);
        failure.put("phase", calibration ? "calibration_reference" : phase.label);
        failure.put("driver_mode",
                DriverExecutionIdentity.mode(phase.usesCustomDriver()));
        failure.put("driver_role", phase.executionRole());
        failure.put("driver_display_name", phase.driver == null
                ? JSONObject.NULL : phase.driver.displayName());
        failure.put("driver_sha256", phase.driver == null
                ? JSONObject.NULL : phase.driver.sha256);
        failure.put("round", phase.round);
        failure.put("workload_id", workloadId);
        failure.put("workload_version", workloadVersion);
        if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) failure.put("trace_id", traceId);
        failure.put("failure_type", failureType);
        failure.put("error", error);
        failure.put("finished_at_ms", System.currentTimeMillis());
        RunnerProcessState.attachToSyntheticFailure(
                failure, currentResultFile, "runner_process");
        ResultFiles.writeAtomic(currentResultFile, failure.toString(2));
        if (!calibration) {
            if (sampleSizePilotActive) sampleSizePilotResults.put(failure);
            else phaseResults.put(failure);
        }
        return failure;
    }

    private void finishCalibrationFailure(JSONObject failure) throws Exception {
        calibrationProbeActive = false;
        calibrationFailure = new JSONObject(failure.toString());
        calibrationObservations.put(new JSONObject()
                .put("stage", calibrationStage)
                .put("success", false)
                .put("failure_type", failure.optString("failure_type", "calibration_failure"))
                .put("failure_stage", failure.optString("failure_stage", "runner_process"))
                .put("runner_last_stage", failure.opt("runner_last_stage")));
        phaseResults.put(failure);
        finishSuite();
    }

    private void killTimedOutRunner() {
        try {
            JSONObject state = RunnerProcessState.read(currentResultFile);
            if (state == null) return;
            int pid = state.optInt("pid", -1);
            if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
        } catch (Exception ignored) {
            // The failure remains recorded even if the stale runner cannot be killed.
        }
    }

    private void finishSuite() {
        try {
            JSONObject candidateJson = candidate == null ? null : candidate.toJson();
            JSONObject referenceJson = reference == null ? null : reference.toJson();
            JSONObject driverIdentityAudit = ValidationDriverIdentity.auditPhases(
                    phaseResults, candidateJson, referenceJson);
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
            report.put("workload_version", workloadVersion);
            report.put("workload", compatibilityWorkloadName());
            report.put("metric_limitations", WorkloadContract.limitationFor(workloadId));
            report.put("workload_config", reportedWorkloadConfig());
            JSONObject dynamicRange = dynamicRangeReport();
            report.put("dynamic_range_calibration", dynamicRange == null
                    ? JSONObject.NULL : dynamicRange);
            report.put("sample_duration_gate", dynamicRange == null
                    ? JSONObject.NULL : dynamicRange.opt("sample_duration_gate"));
            report.put("sample_size_plan", sampleSizePlan == null
                    ? JSONObject.NULL : finalizedSampleSizePlan());
            report.put("thermal_drift", dynamicRange == null
                    ? JSONObject.NULL : dynamicRange.opt("thermal_drift"));
            report.put("operational_duration", new JSONObject()
                    .put("budget_seconds",
                            BenchmarkCalibrationContract.DEFAULT_OPERATIONAL_BUDGET_SECONDS)
                    .put("elapsed_seconds", Math.max(0L,
                            (System.currentTimeMillis() - suiteStartedAt) / 1000L))
                    .put("budget_exhausted", operationalBudgetExhausted));
            if (WorkloadContract.TRANSFER_ID.equals(workloadId)
                    || VisualSceneContract.isVisualScene(workloadId)) {
                report.put("warmup_seconds", warmupSeconds);
                report.put("measure_seconds", measureSeconds);
            }
            report.put("host_device", DeviceSnapshot.capture(activity));
            report.put("candidate", candidateJson == null ? JSONObject.NULL : candidateJson);
            report.put("reference", referenceJson == null ? JSONObject.NULL : referenceJson);
            report.put("comparison_mode", reference == null
                    ? "system_vs_turnip" : "turnip_vs_turnip");
            report.put("phases", phaseResults);
            report.put("workload_version_audit", WorkloadVersionIdentity.audit(report));
            report.put("driver_identity_audit", driverIdentityAudit);
            report.put("driver_identity_confidence",
                    driverIdentityAudit.getString("driver_identity_confidence"));
            report.put("driver_identity_policy_version",
                    driverIdentityAudit.getInt("driver_identity_policy_version"));
            report.put("identity_observation_coverage",
                    driverIdentityAudit.getJSONObject("identity_observation_coverage"));
            report.put("loader_isolation_verified",
                    driverIdentityAudit.get("loader_isolation_verified"));

            JSONArray failureCatalog = FailureCatalog.fromPhases(phaseResults);
            JSONObject summary;
            JSONObject capabilityDiff = capabilityDiff();
            JSONObject renderCorrectness = null;
            JSONObject statisticalAnalysis = null;
            JSONObject traceReplay = null;
            JSONObject visualScene = null;
            String verdict;
            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
                renderCorrectness = analyzeRenderCorrectness(failureCatalog);
                summary = correctionSummary(renderCorrectness, failureCatalog);
                verdict = correctionVerdict(renderCorrectness, failureCatalog);
            } else {
                summary = (WorkloadContract.isPhase2(workloadId)
                        || WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)
                        || VisualSceneContract.isVisualScene(workloadId))
                        ? Phase2Metrics.summarize(phaseResults, workloadId)
                        : summarizeTransfer();
                statisticalAnalysis = StatisticalComparison.analyze(
                        phaseResults, workloadId);
                if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
                    traceReplay = TraceReplayAnalysis.analyze(phaseResults, rounds, mode);
                    appendTraceFailures(failureCatalog, traceReplay);
                    verdict = TraceReplayAnalysis.verdictFor(
                            traceReplay, statisticalAnalysis, mode);
                } else if (VisualSceneContract.isVisualScene(workloadId)) {
                    visualScene = VisualSceneAnalysis.analyze(
                            phaseResults, suiteDirectory, workloadId, rounds, mode,
                            pixelTolerance, maximumDivergentBlocks);
                    appendVisualFailures(failureCatalog, visualScene);
                    verdict = VisualSceneAnalysis.verdictFor(
                            visualScene, statisticalAnalysis, mode);
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
                    ? TraceReplayContract.contractJson(traceId, workloadVersion)
                    : JSONObject.NULL);
            report.put("trace_replay",
                    traceReplay == null ? JSONObject.NULL : traceReplay);
            report.put("visual_scene_contract",
                    VisualSceneContract.isVisualScene(workloadId)
                            ? VisualSceneContract.definition(workloadId, workloadVersion)
                            : JSONObject.NULL);
            report.put("visual_scene",
                    visualScene == null ? JSONObject.NULL : visualScene);
            report.put("capability_diff",
                    capabilityDiff == null ? JSONObject.NULL : capabilityDiff);
            report.put("failure_catalog", failureCatalog);
            report.put("verdict", verdict);
            JSONArray validityWarnings = buildWarnings(
                    renderCorrectness, failureCatalog, statisticalAnalysis,
                    traceReplay, visualScene);
            if (!driverIdentityAudit.optBoolean("eligible_for_aggregation", false)) {
                validityWarnings.put("driver_identity_not_runtime_confirmed:"
                        + driverIdentityAudit.optString("driver_identity_confidence",
                        DriverIdentityPolicy.INFERRED));
            }
            if (dynamicRange != null
                    && !dynamicRange.optBoolean("ranking_eligible", false)) {
                validityWarnings.put("dynamic_range_not_eligible:"
                        + dynamicRange.optString("status",
                        BenchmarkCalibrationContract.STATUS_NOT_CALIBRATED));
            }
            if (operationalBudgetExhausted) {
                validityWarnings.put("operational_duration_budget_exhausted");
            }
            report.put("validity_warnings", validityWarnings);
            report.put("phase4_contract", Phase4Contract.contractJson());
            report.put("phase6_contract", campaignContext == null
                    ? JSONObject.NULL : Phase6Contract.contractJson());
            report.put("campaign_context", campaignContext == null
                    ? JSONObject.NULL : campaignContext);
            report.put("phase7_contract", qualificationContext == null
                    ? JSONObject.NULL : Phase7Contract.contractJson());
            report.put("phase8_contract", qualificationContext != null
                    && qualificationContext.optInt("profile_version", 1)
                            >= Phase8Contract.CURRENT_FULL_PROFILE_VERSION
                    ? Phase8Contract.contractJson() : JSONObject.NULL);
            report.put("phase9_contract", Phase9Contract.contractJson());
            report.put("phase10_contract", Phase10Contract.contractJson());
            report.put("phase11_contract", qualificationContext != null
                    && qualificationContext.optInt("profile_version", 1) >= 3
                    ? Phase11Contract.contractJson() : JSONObject.NULL);
            report.put("qualification_context", qualificationContext == null
                    ? JSONObject.NULL : qualificationContext);
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
        } else if (VisualSceneContract.isVisualScene(workloadId)) {
            return VisualSceneContract.workloadConfig(
                    workloadId, workloadVersion, warmupSeconds, measureSeconds,
                    pixelTolerance, maximumDivergentBlocks);
        } else if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
            config.put("warmup_seconds", warmupSeconds);
            config.put("measure_seconds", measureSeconds);
            config.put("primary_metric", WorkloadContract.TRACE_REPLAY_METRIC);
            config.put("trace", TraceReplayContract.definition(traceId, workloadVersion));
        } else {
            config.put("warmup_seconds", warmupSeconds);
            config.put("measure_seconds", measureSeconds);
            config.put("primary_metric", WorkloadContract.primaryMetricFor(workloadId));
        }
        return config;
    }

    private JSONObject reportedWorkloadConfig() throws Exception {
        JSONObject config = new JSONObject(workloadConfig().toString());
        if (BenchmarkCalibrationContract.requiresCalibration(workloadId, workloadVersion)) {
            config.put("repetition_unit",
                            BenchmarkCalibrationContract.repetitionUnit(workloadId))
                    .put("repetitions_per_sample", calibrationRepetitions)
                    .put("calibration_key_sha256", calibrationKey == null
                            ? JSONObject.NULL
                            : calibrationKey.opt("calibration_key_sha256"))
                    .put("calibration_thermal_profile", calibrationThermalProfile);
        }
        return config;
    }

    private JSONObject dynamicRangeReport() throws Exception {
        if (!BenchmarkCalibrationContract.requiresCalibration(workloadId, workloadVersion)) {
            return null;
        }
        JSONObject linearityResult = linearity == null
                ? BenchmarkCalibrationContract.linearity(Double.NaN, Double.NaN)
                : linearity;
        JSONObject gate = BenchmarkCalibrationContract.sampleDurationGate(
                calibratedMedianUs, linearityResult);
        JSONArray orderedReference = new JSONArray();
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.optJSONObject(index);
            if (phase == null || DriverExecutionIdentity.isCandidateArm(phase)) continue;
            double value = extractMedianBatchUs(phase);
            if (Double.isFinite(value) && value > 0.0) orderedReference.put(value);
        }
        JSONObject thermal = BenchmarkCalibrationContract.thermalDrift(orderedReference);
        JSONObject drift = BenchmarkCalibrationContract.calibrationDrift(
                calibratedMedianUs, postValidationMedianUs);
        boolean eligible = gate.optBoolean("ranking_eligible", false)
                && !Boolean.TRUE.equals(thermal.opt("thermal_drift_detected"))
                && !drift.optBoolean("calibration_drift", true)
                && !operationalBudgetExhausted;
        String status = calibrationFailure != null ? "calibration_failed"
                : eligible ? "valid"
                : operationalBudgetExhausted ? "operational_budget_exhausted"
                : drift.optBoolean("calibration_drift", true) ? "calibration_drift"
                : Boolean.TRUE.equals(thermal.opt("thermal_drift_detected"))
                ? "thermal_drift_detected" : gate.optString("classification");
        JSONObject output = new JSONObject()
                .put("calibration_policy_version", BenchmarkCalibrationContract.POLICY_VERSION)
                .put("calibration_key", calibrationKey)
                .put("target_min_us", BenchmarkCalibrationContract.TARGET_MIN_US)
                .put("target_max_us", BenchmarkCalibrationContract.TARGET_MAX_US)
                .put("target_center_us", BenchmarkCalibrationContract.TARGET_CENTER_US)
                .put("validity_floor_us", BenchmarkCalibrationContract.VALIDITY_FLOOR_US)
                .put("repetition_unit",
                        BenchmarkCalibrationContract.repetitionUnit(workloadId))
                .put("repetitions_per_sample", calibrationRepetitions)
                .put("calibrated_median_us", finiteOrNull(calibratedMedianUs))
                .put("linearity", linearityResult)
                .put("sample_duration_gate", gate)
                .put("calibration_observations", calibrationObservations)
                .put("calibration_failure", calibrationFailure == null
                        ? JSONObject.NULL : calibrationFailure)
                .put("effect_injection_validation", effectInjectionObservations)
                .put("post_run_validation", drift)
                .put("thermal_drift", thermal)
                .put("cold_calibration_not_valid_when_hot", true)
                .put("cross_hardware_comparison",
                        "effect_size_only; absolute_normalized_time_forbidden")
                .put("status", status)
                .put("ranking_eligible", eligible);
        if (gate.optBoolean("normalized_duration_allowed", false)) {
            output.put("normalized_duration_per_repetition_us",
                    calibratedMedianUs / calibrationRepetitions);
        } else {
            output.put("normalized_duration_per_repetition_us", JSONObject.NULL);
        }
        return output;
    }

    private JSONObject finalizedSampleSizePlan() throws Exception {
        JSONObject output = new JSONObject(sampleSizePlan.toString());
        int completed = completedPairCount(phaseResults);
        output.put("completed_paired_rounds", completed);
        if (operationalBudgetExhausted
                || completed < output.optInt("planned_paired_rounds", 0)) {
            output.put("sample_size_status", "insufficient_sensitivity");
        }
        return output;
    }

    private int completedPairCount(JSONArray source) {
        int completed = 0;
        for (int round = 1; round <= rounds; ++round) {
            if (findSuccessfulPhase(source, round, false) != null
                    && findSuccessfulPhase(source, round, true) != null) completed++;
        }
        return completed;
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }

    private String compatibilityWorkloadName() {
        return WorkloadContract.nativeNameFor(workloadId, workloadVersion);
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
            if (DriverExecutionIdentity.isCandidateArm(phase)) {
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
                collectHash(phase, DriverExecutionIdentity.isCandidateArm(phase)
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

    private JSONObject firstCapabilities(boolean candidateArm) {
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            if (candidateArm != DriverExecutionIdentity.isCandidateArm(phase)) continue;
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult == null) continue;
            JSONObject capabilities = nativeResult.optJSONObject("capabilities");
            if (capabilities != null) return capabilities;
        }
        return null;
    }

    private JSONObject findSuccessfulPhase(int round, boolean candidateArm) {
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            if (phase.optInt("round", -1) != round) continue;
            if (candidateArm == DriverExecutionIdentity.isCandidateArm(phase)) return phase;
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

    private void appendVisualFailures(JSONArray failures, JSONObject visualScene)
            throws Exception {
        JSONArray comparisons = visualScene.optJSONArray("comparisons");
        if (comparisons != null) {
            for (int index = 0; index < comparisons.length(); ++index) {
                JSONObject comparison = comparisons.optJSONObject(index);
                if (comparison == null || comparison.optBoolean("passed", true)) continue;
                FailureCatalog.appendVisualMismatch(
                        failures,
                        comparison.optInt("round", -1),
                        comparison.optInt("checkpoint_frame", -1),
                        comparison);
            }
        }
        if (visualScene.optBoolean("system_nondeterministic", false)) {
            failures.put(new JSONObject()
                    .put("phase", "system")
                    .put("driver_mode", "system")
                    .put("failure_type", "visual_scene_nondeterminism")
                    .put("failure_stage", "visual_checkpoint_gate")
                    .put("message", "O braço do sistema variou entre checkpoints equivalentes."));
        }
        if (visualScene.optBoolean("candidate_nondeterministic", false)) {
            failures.put(new JSONObject()
                    .put("phase", "candidate")
                    .put("driver_mode", "custom")
                    .put("failure_type", "visual_scene_nondeterminism")
                    .put("failure_stage", "visual_checkpoint_gate")
                    .put("message", "O candidato variou entre checkpoints equivalentes."));
        }
    }

    private JSONArray buildWarnings(JSONObject correction, JSONArray failureCatalog,
                                      JSONObject statisticalAnalysis, JSONObject traceReplay,
                                      JSONObject visualScene) throws Exception {
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
            if (WorkloadContract.TRANSFER_ID.equals(workloadId)
                    || VisualSceneContract.isVisualScene(workloadId)) {
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
        if (visualScene != null) {
            if (visualScene.optBoolean("system_nondeterministic", false)
                    || visualScene.optBoolean("candidate_nondeterministic", false)) {
                warnings.put("Cena visual não determinística: checkpoints variaram no mesmo braço.");
            }
            if (visualScene.optInt("checkpoint_mismatch_count", 0) > 0) {
                warnings.put("Checkpoints visuais divergiram; conclusão de performance bloqueada.");
            }
            if (mode == MODE_AB && visualScene.optInt("complete_comparison_count", 0)
                    < visualScene.optInt("expected_comparison_count", 0)) {
                warnings.put("Cena visual sem todos os checkpoints A/B completos.");
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
        return reference == null ? "ab_system_vs_candidate"
                : "ab_reference_turnip_vs_candidate_turnip";
    }
}
