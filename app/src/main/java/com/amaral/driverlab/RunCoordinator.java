package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    private final Listener listener;
    private final List<Phase> phases = new ArrayList<>();
    private final JSONArray phaseResults = new JSONArray();

    private File suiteDirectory;
    private long suiteStartedAt;
    private int phaseIndex;
    private long phaseDeadlineElapsed;
    private File currentResultFile;

    RunCoordinator(Activity activity, DriverPackage candidate, int mode, int rounds,
                   int warmupSeconds, int measureSeconds, Listener listener) {
        this.activity = activity;
        this.candidate = candidate;
        this.mode = mode;
        this.rounds = Math.max(1, Math.min(rounds, 10));
        this.warmupSeconds = Math.max(0, Math.min(warmupSeconds, 30));
        this.measureSeconds = Math.max(1, Math.min(measureSeconds, 120));
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
        listener.onStatus("Executando " + (phaseIndex + 1) + "/" + phases.size()
                + " · rodada " + phase.round + " · "
                + (phase.custom ? candidate.displayName() : "driver do sistema"));

        Intent intent = new Intent(activity, RunnerActivity.class);
        intent.putExtra(RunnerActivity.EXTRA_RESULT_PATH, currentResultFile.getAbsolutePath());
        intent.putExtra(RunnerActivity.EXTRA_PHASE_LABEL, phase.label);
        intent.putExtra(RunnerActivity.EXTRA_ROUND, phase.round);
        intent.putExtra(RunnerActivity.EXTRA_WARMUP_SECONDS, warmupSeconds);
        intent.putExtra(RunnerActivity.EXTRA_MEASURE_SECONDS, measureSeconds);
        if (phase.custom) {
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_DIR, candidate.directory.getAbsolutePath());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_NAME, candidate.libraryName);
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_META, candidate.metadata.toString());
            intent.putExtra(RunnerActivity.EXTRA_DRIVER_SHA, candidate.sha256);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        phaseDeadlineElapsed = SystemClock.elapsedRealtime()
                + (warmupSeconds + measureSeconds + 45L) * 1000L;
        activity.startActivity(intent);
        handler.postDelayed(this::pollCurrent, 500);
    }

    private void pollCurrent() {
        try {
            if (currentResultFile.isFile()) {
                phaseResults.put(new JSONObject(ResultFiles.readUtf8(currentResultFile)));
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1500);
                return;
            }
            if (SystemClock.elapsedRealtime() >= phaseDeadlineElapsed) {
                killTimedOutRunner();
                Phase phase = phases.get(phaseIndex);
                JSONObject timeout = new JSONObject();
                timeout.put("success", false);
                timeout.put("phase", phase.label);
                timeout.put("round", phase.round);
                timeout.put("error", "runner_timeout");
                timeout.put("finished_at_ms", System.currentTimeMillis());
                phaseResults.put(timeout);
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1500);
                return;
            }
            handler.postDelayed(this::pollCurrent, 500);
        } catch (Throwable error) {
            listener.onFailure("Falha ao ler o resultado da fase", error);
        }
    }

    private void killTimedOutRunner() {
        try {
            File stateFile = new File(currentResultFile.getAbsolutePath() + ".state");
            if (!stateFile.isFile()) return;
            int pid = new JSONObject(ResultFiles.readUtf8(stateFile)).optInt("pid", -1);
            if (pid > 0 && pid != Process.myPid()) {
                Process.killProcess(pid);
            }
        } catch (Exception ignored) {
            // The timeout itself remains recorded even if the stale runner cannot be killed.
        }
    }

    private void finishSuite() {
        try {
            JSONObject report = new JSONObject();
            report.put("schema_version", 1);
            report.put("suite_id", suiteDirectory.getName());
            report.put("app_version", BuildConfig.VERSION_NAME);
            report.put("started_at_ms", suiteStartedAt);
            report.put("finished_at_ms", System.currentTimeMillis());
            report.put("mode", modeName());
            report.put("rounds", rounds);
            report.put("warmup_seconds", warmupSeconds);
            report.put("measure_seconds", measureSeconds);
            report.put("order_policy", mode == MODE_AB ? "AB/BA alternating" : "single driver");
            report.put("workload", "vulkan_transfer_stress_v1");
            report.put("host_device", DeviceSnapshot.capture(activity));
            report.put("candidate", candidate == null ? JSONObject.NULL : candidate.toJson());
            report.put("phases", phaseResults);
            report.put("summary", summarize());
            report.put("validity_warnings", buildWarnings());

            File reportFile = new File(suiteDirectory, "suite.json");
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            listener.onComplete(reportFile, report);
        } catch (Throwable error) {
            listener.onFailure("Falha ao consolidar a suíte", error);
        }
    }

    private JSONObject summarize() throws Exception {
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
            double value = nativeResult.optDouble("transfer_payload_gib_s", Double.NaN);
            if (!Double.isFinite(value)) continue;
            if ("custom".equals(phase.optString("driver_mode"))) {
                candidateValues.add(value);
            } else {
                system.add(value);
            }
        }

        JSONObject summary = new JSONObject();
        summary.put("system", statistics(system));
        summary.put("candidate", statistics(candidateValues));
        summary.put("failed_phases", failures);
        if (!system.isEmpty() && !candidateValues.isEmpty()) {
            double systemMedian = median(system);
            double candidateMedian = median(candidateValues);
            summary.put("candidate_vs_system_percent",
                    (candidateMedian / systemMedian - 1.0) * 100.0);
        } else {
            summary.put("candidate_vs_system_percent", JSONObject.NULL);
        }
        return summary;
    }

    private JSONObject statistics(List<Double> values) throws Exception {
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

    private static double median(List<Double> input) {
        List<Double> values = new ArrayList<>(input);
        Collections.sort(values);
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0
                : values.get(middle);
    }

    private JSONArray buildWarnings() throws Exception {
        JSONArray warnings = new JSONArray();
        double minimumTemperature = Double.POSITIVE_INFINITY;
        double maximumTemperature = Double.NEGATIVE_INFINITY;
        boolean timestampFallback = false;
        for (int index = 0; index < phaseResults.length(); ++index) {
            JSONObject phase = phaseResults.getJSONObject(index);
            if (!phase.optBoolean("success", false)) {
                warnings.put("Uma ou mais fases falharam ou expiraram.");
            }
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult != null && !nativeResult.optBoolean("gpu_timestamps_used", false)) {
                timestampFallback = true;
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
        if (timestampFallback) {
            warnings.put("A GPU não forneceu timestamps válidos; a fase usou relógio de parede.");
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
        return warnings;
    }

    private String modeName() {
        if (mode == MODE_SYSTEM) return "system_only";
        if (mode == MODE_CUSTOM) return "candidate_only";
        return "ab_system_vs_candidate";
    }
}
