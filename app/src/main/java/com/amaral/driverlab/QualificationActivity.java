package com.amaral.driverlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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

public final class QualificationActivity extends Activity
        implements QualificationCoordinator.Listener {
    private static final int REQUEST_EXPORT_BUNDLE = 7001;

    private final List<DriverPackage> drivers = new ArrayList<>();
    private Spinner driverSpinner;
    private TextView status;
    private TextView preview;
    private Button startButton;
    private Button resumeButton;
    private Button pauseButton;
    private Button exportButton;
    private QualificationCoordinator coordinator;
    private File currentQualificationFile;
    private File currentBundleFile;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadDrivers();
        findLatestQualification();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Teste Full Recomendado · Fase 11", 25, true));
        TextView subtitle = text(
                "Executa o Full v3 com cenas visíveis, todos os testes anteriores, diagnóstico profundo, soak curto e bundle completo.",
                14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, margins(0, 4, 0, 14));

        TextView note = text("Perfil imutável: " + Phase11Contract.PROFILE_LABEL
                + "\n" + QualificationProfile.steps().size() + " blocos orquestrados · "
                + Phase11Contract.AUTOMATED_LOGICAL_TESTS + " testes automáticos · "
                + "1 evidência real opcional.\nDiagnóstico profundo: 128 MiB · soak curto: 5 ciclos.\n\n"
                + Phase11Contract.LIMITATION, 13, false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(0, 0, 0, 16));

        root.addView(text("Driver candidato", 18, true));
        driverSpinner = new Spinner(this);
        root.addView(driverSpinner, margins(0, 6, 0, 16));

        startButton = button("▶ INICIAR TESTE FULL RECOMENDADO", view -> preflightAndStart());
        resumeButton = button("RETOMAR TESTE FULL INCOMPLETO", view -> resume());
        pauseButton = button("PAUSAR APÓS A ETAPA ATUAL", view -> pause());
        exportButton = button("EXPORTAR LOG FULL (.ZIP)", view -> chooseExport());
        Button refreshButton = button("ATUALIZAR RESULTADO", view -> refresh());
        root.addView(startButton);
        root.addView(resumeButton, margins(0, 5, 0, 0));
        root.addView(pauseButton, margins(0, 5, 0, 0));
        root.addView(exportButton, margins(0, 5, 0, 0));
        root.addView(refreshButton, margins(0, 5, 0, 16));

        status = text("Pronto.", 14, true);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 0, 0, 10));
        preview = text("", 12, false);
        preview.setTextIsSelectable(true);
        preview.setBackgroundColor(0xffeeeeee);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(preview);
        setContentView(scroll);
        updateButtons();
    }

    private void loadDrivers() {
        drivers.clear();
        File root = new File(getFilesDir(), "drivers");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                if (directory.getName().startsWith(".partial-")) continue;
                try {
                    DriverPackage driver = DriverPackage.fromJson(
                            ResultFiles.readUtf8(new File(directory, "descriptor.json")));
                    if (driver.isUsable()) drivers.add(driver);
                } catch (Exception ignored) {
                    // Invalid packages remain unavailable.
                }
            }
        }
        drivers.sort(Comparator.comparing(DriverPackage::displayName));
        List<String> labels = new ArrayList<>();
        if (drivers.isEmpty()) labels.add("Importe um driver na tela principal");
        for (DriverPackage driver : drivers) {
            labels.add(driver.displayName() + " · " + driver.sha256.substring(0, 12));
        }
        driverSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        updateButtons();
    }

    private void preflightAndStart() {
        DriverPackage driver = selectedDriver();
        if (driver == null) {
            status.setText("Importe um driver Turnip válido primeiro.");
            return;
        }
        try {
            JSONObject preflight = QualificationPreflight.capture(this);
            JSONObject evaluation = preflight.getJSONObject("evaluation");
            if (!evaluation.optBoolean("eligible_to_start", true)) {
                new AlertDialog.Builder(this)
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
                    getFilesDir(), driver, preflight);
            currentBundleFile = null;
            beginCoordinator();
        } catch (Throwable error) {
            status.setText("Falha ao criar o teste Full: " + error.getMessage());
        }
    }

    private void findLatestQualification() {
        File incomplete = QualificationStore.findLatestIncomplete(getFilesDir());
        if (incomplete != null) currentQualificationFile = incomplete;
        else {
            File root = new File(getFilesDir(), "qualifications");
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null && dirs.length > 0) {
                java.util.Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
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

    @Override
    public void onStatus(String message) {
        status.setText(message);
    }

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
            status.append("\nTeste Full concluído. O log completo está pronto para exportação.");
        } catch (Exception error) {
            status.setText("Teste concluído: " + error.getMessage());
        }
        updateButtons();
    }

    @Override
    public void onFailure(String message, Throwable error) {
        setBusy(false);
        status.setText(message + ": " + (error == null ? "falha desconhecida" : error.getMessage()));
        updateButtons();
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
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
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
