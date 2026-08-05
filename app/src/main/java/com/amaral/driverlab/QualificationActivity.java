package com.amaral.driverlab;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class QualificationActivity extends LocalizedActivity
        implements QualificationCoordinator.Listener {
    private static final int REQUEST_EXPORT_BUNDLE = 7001;
    static final String EXTRA_GUIDED = "guided";
    static final String EXTRA_AUTOSTART = "autostart";
    static final String EXTRA_DRIVER_SHA = "driver_sha";
    static final String EXTRA_REFERENCE_DRIVER_SHA = "reference_driver_sha";
    static final String EXTRA_COMPARISON_MODE = "comparison_mode";
    static final String EXTRA_OPEN_LOG_ON_COMPLETE = "open_log_on_complete";
    static final String EXTRA_PROFILE_VERSION = "profile_version";

    private final List<DriverPackage> drivers = new ArrayList<>();
    private Spinner driverSpinner;
    private TextView comparisonSummary;
    private TextView status;
    private TextView preview;
    private Button startButton;
    private Button resumeButton;
    private Button pauseButton;
    private Button exportButton;
    private ProgressBar overallProgress;
    private QualificationCoordinator coordinator;
    private File currentQualificationFile;
    private File currentBundleFile;
    private DriverPackage referenceDriver;
    private String comparisonMode = "system_vs_turnip";
    private boolean guidedLaunch;
    private int profileVersion = QualificationProfile.currentVersion();
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppTheme.apply(this);
        guidedLaunch = getIntent().getBooleanExtra(EXTRA_GUIDED, false);
        profileVersion = getIntent().getIntExtra(EXTRA_PROFILE_VERSION,
                QualificationProfile.currentVersion());
        try { QualificationProfile.stepsForVersion(profileVersion); }
        catch (IllegalArgumentException ignored) {
            profileVersion = QualificationProfile.currentVersion();
        }
        comparisonMode = "turnip_vs_turnip".equals(
                getIntent().getStringExtra(EXTRA_COMPARISON_MODE))
                ? "turnip_vs_turnip" : "system_vs_turnip";
        buildUi();
        loadDrivers();
        selectDriverBySha(getIntent().getStringExtra(EXTRA_DRIVER_SHA));
        referenceDriver = DriverCatalog.findBySha(this,
                getIntent().getStringExtra(EXTRA_REFERENCE_DRIVER_SHA));
        updateComparisonSummary();
        findLatestQualification();
        if (getIntent().getBooleanExtra(EXTRA_AUTOSTART, false)) {
            startButton.post(() -> {
                if (!busy && selectedDriver() != null) preflightAndStart();
            });
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AmaralColors.BACKGROUND);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        boolean legacyFull = profileVersion == Phase11Contract.PROFILE_VERSION;
        root.addView(text(legacyFull ? getString(R.string.phase13_legacy_full_title)
                : getString(R.string.phase13_full_title), 25, true));
        TextView subtitle = text(legacyFull
                ? getString(R.string.phase13_legacy_full_detail)
                : getString(R.string.phase13_quick_test_description), 14, false);
        subtitle.setTextColor(AmaralColors.TEXT_SECONDARY);
        root.addView(subtitle, margins(0, 4, 0, 12));

        comparisonSummary = text("", 14, true);
        comparisonSummary.setBackground(AppTheme.rounded(AmaralColors.SURFACE_HIGHLIGHT,
                14, AmaralColors.BORDER, 1, this));
        comparisonSummary.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(comparisonSummary, margins(0, 0, 0, 14));

        TextView note = text("Perfil imutável: " + (legacyFull
                ? Phase11Contract.PROFILE_LABEL : Phase13ValidationContract.PROFILE_LABEL)
                + "\n" + QualificationProfile.stepsForVersion(profileVersion).size()
                + " etapas orquestradas.\n\n"
                + (legacyFull ? Phase11Contract.LIMITATION
                : Phase13ValidationContract.LIMITATION), 13, false);
        note.setTextColor(AmaralColors.TEXT_SECONDARY);
        if (!guidedLaunch) root.addView(note, margins(0, 0, 0, 16));

        TextView driverLabel = text("Driver candidato", 18, true);
        driverSpinner = new Spinner(this);
        if (guidedLaunch) {
            driverLabel.setVisibility(View.GONE);
            driverSpinner.setVisibility(View.GONE);
        }
        root.addView(driverLabel);
        root.addView(driverSpinner, margins(0, 6, 0, 16));

        startButton = button(legacyFull ? getString(R.string.phase13_start_legacy_full)
                : getString(R.string.phase13_start_selected_test), view -> preflightAndStart());
        resumeButton = button("RETOMAR TESTE FULL INCOMPLETO", view -> resume());
        pauseButton = button("PAUSAR APÓS A ETAPA ATUAL", view -> pause());
        exportButton = button("EXPORTAR LOG FULL (.ZIP)", view -> chooseExport());
        Button logButton = button(getString(R.string.phase13_open_test_log), view -> openLog(false));
        Button refreshButton = button("ATUALIZAR RESULTADO", view -> refresh());
        root.addView(startButton);
        if (guidedLaunch) {
            startButton.setVisibility(View.GONE);
            resumeButton.setVisibility(View.GONE);
            exportButton.setVisibility(View.GONE);
            logButton.setVisibility(View.GONE);
            refreshButton.setVisibility(View.GONE);
        }
        root.addView(resumeButton, margins(0, 5, 0, 0));
        root.addView(pauseButton, margins(0, 5, 0, 0));
        root.addView(exportButton, margins(0, 5, 0, 0));
        root.addView(logButton, margins(0, 5, 0, 0));
        root.addView(refreshButton, margins(0, 5, 0, 16));

        overallProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overallProgress.setMax(QualificationProfile.stepsForVersion(profileVersion).size());
        overallProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                AmaralColors.BRAND_SECONDARY));
        overallProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                AmaralColors.SURFACE_HIGHLIGHT));
        overallProgress.setContentDescription(
                getString(R.string.phase13_execution_progress_description));
        root.addView(overallProgress, margins(0, 0, 0, 14));

        status = text("Pronto.", 14, true);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 0, 0, 10));
        preview = text("", 12, false);
        preview.setTextIsSelectable(true);
        preview.setBackgroundColor(AmaralColors.SURFACE);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(preview);
        setContentView(scroll);
        updateButtons();
    }

    private void loadDrivers() {
        drivers.clear();
        drivers.addAll(DriverCatalog.load(this));
        List<String> labels = new ArrayList<>();
        if (drivers.isEmpty()) labels.add("Importe um driver na tela principal");
        for (DriverPackage driver : drivers) {
            labels.add(driver.displayName() + " · " + driver.sha256.substring(0, 12));
        }
        driverSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                updateComparisonSummary();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateButtons();
    }

    private void updateComparisonSummary() {
        if (comparisonSummary == null) return;
        DriverPackage candidate = selectedDriver();
        String candidateLabel = candidate == null ? "—" : candidate.displayName();
        String referenceLabel = referenceDriver == null
                ? getString(R.string.phase13_system_driver) : referenceDriver.displayName();
        comparisonSummary.setText(getString(R.string.phase13_comparison_summary_format,
                referenceLabel, candidateLabel));
    }

    private void preflightAndStart() {
        DriverPackage driver = selectedDriver();
        if (driver == null) {
            status.setText("Importe um driver Turnip válido primeiro.");
            return;
        }
        if ("turnip_vs_turnip".equals(comparisonMode)) {
            if (referenceDriver == null) {
                status.setText(getString(R.string.phase13_select_reference_error));
                return;
            }
            if (driver.sha256.equals(referenceDriver.sha256)) {
                status.setText(getString(R.string.phase13_same_driver_error));
                return;
            }
        }
        try {
            JSONObject preflight = QualificationPreflight.capture(this);
            JSONObject evaluation = preflight.getJSONObject("evaluation");
            if (!evaluation.optBoolean("eligible_to_start", true)) {
                new LocalizedAlertDialogBuilder(this)
                        .setTitle("Condições fora do recomendado")
                        .setMessage("O teste pode ser executado para diagnóstico, mas ficará "
                                + "bloqueado para ranking.\n\n"
                                + evaluation.getJSONArray("blockers").toString(2))
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Executar mesmo assim",
                                (dialog, which) -> createAndRun(driver, preflight))
                        .show();
            } else {
                createAndRun(driver, preflight);
            }
        } catch (Throwable error) {
            status.setText("Falha no preflight: " + error.getMessage());
        }
    }

    private void createAndRun(DriverPackage driver, JSONObject preflight) {
        try {
            currentQualificationFile = QualificationStore.create(
                    getFilesDir(), driver, referenceDriver, comparisonMode,
                    profileVersion, preflight);
            currentBundleFile = null;
            beginCoordinator();
        } catch (Throwable error) {
            status.setText("Falha ao criar o teste: " + error.getMessage());
        }
    }

    private void findLatestQualification() {
        File incomplete = QualificationStore.findLatestIncomplete(getFilesDir());
        if (incomplete != null) currentQualificationFile = incomplete;
        else {
            File root = new File(getFilesDir(), "qualifications");
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null && dirs.length > 0) {
                java.util.Arrays.sort(dirs,
                        Comparator.comparingLong(File::lastModified).reversed());
                File latest = new File(dirs[0], "qualification.json");
                if (latest.isFile()) currentQualificationFile = latest;
            }
        }
        refresh();
    }

    private void resume() {
        if (currentQualificationFile == null || !currentQualificationFile.isFile()) {
            currentQualificationFile = QualificationStore.findLatestIncomplete(getFilesDir());
        }
        if (currentQualificationFile == null) {
            status.setText("Nenhum teste Full incompleto encontrado.");
            return;
        }
        beginCoordinator();
    }

    private void beginCoordinator() {
        setBusy(true);
        coordinator = new QualificationCoordinator(this, currentQualificationFile, this);
        coordinator.startOrResume();
        updateButtons();
    }

    private void pause() {
        if (coordinator == null || !coordinator.isActive()) {
            status.setText("Nenhum teste Full está em execução.");
            return;
        }
        coordinator.pauseAfterCurrent();
    }

    private void refresh() {
        if (currentQualificationFile == null || !currentQualificationFile.isFile()) {
            updateButtons();
            return;
        }
        try {
            JSONObject manifest = QualificationStore.load(currentQualificationFile);
            showManifest(manifest);
            JSONObject bundle = manifest.optJSONObject("diagnostic_bundle");
            currentBundleFile = bundle == null ? null
                    : new File(currentQualificationFile.getParentFile(),
                    bundle.optString("relative_path", "diagnostic-bundle.zip"));
        } catch (Throwable error) {
            status.setText("Falha ao ler qualification.json: " + error.getMessage());
        }
        updateButtons();
    }

    private void showManifest(JSONObject manifest) throws Exception {
        JSONObject execution = manifest.getJSONObject("execution");
        int completed = QualificationStore.countStatus(manifest, "completed");
        int failed = QualificationStore.countStatus(manifest, "failed");
        int pending = QualificationStore.countStatus(manifest, "pending");
        if (overallProgress != null) overallProgress.setProgress(completed);
        status.setText(manifest.getString("qualification_id") + " · "
                + execution.optString("state") + "\nEtapas: " + completed + " concluídas · "
                + failed + " falhas · " + pending + " pendentes\nPerfil SHA-256: "
                + manifest.optString("profile_sha256"));
        JSONObject report = manifest.optJSONObject("report");
        if (report == null) {
            preview.setText(manifest.toString(2));
            return;
        }
        JSONObject summary = report.optJSONObject("human_summary");
        JSONObject score = report.optJSONObject("score");
        StringBuilder text = new StringBuilder();
        text.append(summary == null ? "Resultado" : summary.optString("headline"))
                .append("\n").append(summary == null ? "" : summary.optString("detail"))
                .append("\n\nÍndice de performance: ").append(format(score == null
                        ? Double.NaN : score.optDouble("performance_index", Double.NaN)))
                .append(" / 100\nÍndice de compatibilidade: ").append(format(score == null
                        ? Double.NaN : score.optDouble("compatibility_index", Double.NaN)))
                .append(" / 100\nGanho ponderado: ").append(formatPercent(score == null
                        ? Double.NaN : score.optDouble("weighted_improvement_percent", Double.NaN)))
                .append("\nConfiança: ").append(score == null ? "indisponível"
                        : score.optString("confidence"))
                .append("\nVencedor: ").append(score == null ? "indisponível"
                        : score.optString("winner"));
        JSONArray reasons = score == null ? null : score.optJSONArray("gate_reasons");
        if (reasons != null && reasons.length() > 0) {
            text.append("\n\nBloqueios:");
            for (int index = 0; index < reasons.length(); ++index) {
                text.append("\n• ").append(reasons.optString(index));
            }
        }
        JSONObject leaderboard = report.optJSONObject("local_leaderboard");
        if (leaderboard != null && !leaderboard.isNull("current_rank")) {
            text.append("\n\nRanking local: #").append(leaderboard.optInt("current_rank"))
                    .append(" de ").append(leaderboard.optInt("eligible_entry_count"));
        }
        preview.setText(text.toString());
    }

    private void chooseExport() {
        if (currentBundleFile == null || !currentBundleFile.isFile()) {
            status.setText("O pacote Full ainda não está disponível.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, currentQualificationFile.getParentFile().getName()
                + "-full-log.zip");
        startActivityForResult(intent, REQUEST_EXPORT_BUNDLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_BUNDLE || resultCode != RESULT_OK
                || data == null || data.getData() == null || currentBundleFile == null) return;
        Uri uri = data.getData();
        try (FileInputStream input = new FileInputStream(currentBundleFile);
             OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("Destino indisponível");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.flush();
            status.setText("Log Full exportado com sucesso.");
        } catch (Throwable error) {
            status.setText("Falha ao exportar: " + error.getMessage());
        }
    }

    @Override public void onStatus(String message) { status.setText(message); }

    @Override
    public void onUpdated(File qualificationFile, JSONObject manifest) {
        currentQualificationFile = qualificationFile;
        try {
            showManifest(manifest);
        } catch (Exception error) {
            status.setText("Atualizado, mas a prévia falhou: " + error.getMessage());
        }
        updateButtons();
    }

    @Override
    public void onComplete(File qualificationFile, JSONObject manifest, File bundleFile) {
        currentQualificationFile = qualificationFile;
        currentBundleFile = bundleFile;
        setBusy(false);
        try {
            showManifest(manifest);
            status.append("\nTeste concluído. Abrindo o log completo…");
        } catch (Exception error) {
            status.setText("Teste concluído: " + error.getMessage());
        }
        updateButtons();
        if (getIntent().getBooleanExtra(EXTRA_OPEN_LOG_ON_COMPLETE, false)) {
            overallProgress.postDelayed(() -> openLog(true), 350L);
        }
    }

    @Override
    public void onFailure(String message, Throwable error) {
        setBusy(false);
        status.setText(message + ": " + (error == null ? "falha desconhecida" : error.getMessage()));
        updateButtons();
        if (getIntent().getBooleanExtra(EXTRA_OPEN_LOG_ON_COMPLETE, false)
                && currentQualificationFile != null && currentQualificationFile.isFile()) {
            overallProgress.postDelayed(() -> openLog(true), 700L);
        }
    }

    private void openLog(boolean finishCurrent) {
        if (currentQualificationFile == null || !currentQualificationFile.isFile()) {
            status.setText(getString(R.string.phase13_no_result_yet));
            return;
        }
        Intent intent = new Intent(this, QualificationLogActivity.class);
        intent.putExtra(QualificationLogActivity.EXTRA_QUALIFICATION_PATH,
                currentQualificationFile.getAbsolutePath());
        startActivity(intent);
        if (finishCurrent) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private void selectDriverBySha(String sha) {
        if (sha == null || sha.isEmpty() || driverSpinner == null) return;
        for (int index = 0; index < drivers.size(); index++) {
            if (sha.equals(drivers.get(index).sha256)) {
                driverSpinner.setSelection(index);
                updateComparisonSummary();
                return;
            }
        }
    }

    private DriverPackage selectedDriver() {
        if (drivers.isEmpty()) return null;
        int position = driverSpinner.getSelectedItemPosition();
        return position >= 0 && position < drivers.size() ? drivers.get(position) : drivers.get(0);
    }

    private void setBusy(boolean value) {
        busy = value;
        driverSpinner.setEnabled(!value);
        updateButtons();
    }

    private void updateButtons() {
        boolean active = coordinator != null && coordinator.isActive();
        if (startButton != null) startButton.setEnabled(!busy && !drivers.isEmpty());
        if (resumeButton != null) resumeButton.setEnabled(!active && isCurrentIncomplete());
        if (pauseButton != null) pauseButton.setEnabled(active);
        if (exportButton != null) exportButton.setEnabled(currentBundleFile != null
                && currentBundleFile.isFile());
    }

    private boolean isCurrentIncomplete() {
        if (currentQualificationFile == null || !currentQualificationFile.isFile()) return false;
        try {
            String state = QualificationStore.load(currentQualificationFile)
                    .getJSONObject("execution").optString("state", "pending");
            return !state.startsWith("completed");
        } catch (Exception ignored) {
            return false;
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        return AppTheme.secondaryButton(this, label, listener);
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(AmaralColors.TEXT_PRIMARY);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.1f", value) : "indisponível";
    }

    private String formatPercent(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%+.2f%%", value) : "indisponível";
    }
}
