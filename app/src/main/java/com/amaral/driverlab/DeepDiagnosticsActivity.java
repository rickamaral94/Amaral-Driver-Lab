package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class DeepDiagnosticsActivity extends Activity
        implements DeepDiagnosticsCoordinator.Listener {
    private static final int REQUEST_EXPORT = 10101;

    private final List<DriverPackage> drivers = new ArrayList<>();
    private Spinner driverSpinner;
    private EditText cyclesInput;
    private EditText memoryInput;
    private TextView status;
    private TextView preview;
    private Button fullButton;
    private Button soakButton;
    private Button exportButton;
    private DeepDiagnosticsCoordinator coordinator;
    private File bundleFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadDrivers();
        findLatestBundle();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Diagnóstico Profundo Turnip · Fase 10", 25, true));
        TextView subtitle = text(
                "Matriz de formatos, shaders/cache, memória, sincronização e Soak Test A/B.",
                14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, margins(0, 4, 0, 12));
        TextView note = text(Phase10Contract.LIMITATION, 13, false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(0, 0, 0, 16));

        root.addView(text("Driver candidato", 18, true));
        driverSpinner = new Spinner(this);
        root.addView(driverSpinner, margins(0, 6, 0, 12));

        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.HORIZONTAL);
        cyclesInput = numeric("Ciclos do Soak", String.valueOf(
                Phase10Contract.DEFAULT_SOAK_CYCLES));
        memoryInput = numeric("Limite de memória (MiB)", String.valueOf(
                Phase10Contract.DEFAULT_MEMORY_MIB));
        inputs.addView(cyclesInput, weighted());
        inputs.addView(memoryInput, weighted());
        root.addView(inputs, margins(0, 0, 0, 12));

        fullButton = button("▶ DIAGNÓSTICO PROFUNDO A/B", view -> start("full"));
        soakButton = button("♻ SOAK TEST A/B", view -> start("soak"));
        exportButton = button("EXPORTAR PACOTE COMPLETO (.ZIP)", view -> chooseExport());
        Button contractButton = button("VER CONTRATO DA FASE 10", view -> showContract());
        root.addView(fullButton);
        root.addView(soakButton, margins(0, 5, 0, 0));
        root.addView(exportButton, margins(0, 5, 0, 0));
        root.addView(contractButton, margins(0, 5, 0, 16));

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
                    // Invalid drivers remain unavailable.
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

    private void start(String mode) {
        if (coordinator != null && coordinator.isActive()) return;
        DriverPackage driver = selectedDriver();
        if (driver == null) {
            status.setText("Importe e selecione um driver Turnip válido.");
            return;
        }
        int cycles = parse(cyclesInput, Phase10Contract.DEFAULT_SOAK_CYCLES,
                Phase10Contract.MIN_SOAK_CYCLES, Phase10Contract.MAX_SOAK_CYCLES);
        int memory = parse(memoryInput, Phase10Contract.DEFAULT_MEMORY_MIB,
                Phase10Contract.MIN_MEMORY_MIB, Phase10Contract.MAX_MEMORY_MIB);
        cyclesInput.setText(String.valueOf(cycles));
        memoryInput.setText(String.valueOf(memory));
        bundleFile = null;
        coordinator = new DeepDiagnosticsCoordinator(
                this, driver, mode, cycles, memory, this);
        updateButtons();
        coordinator.start();
    }

    private void showContract() {
        try {
            preview.setText(Phase10Contract.contractJson().toString(2));
        } catch (Exception error) {
            preview.setText(error.toString());
        }
    }

    private void findLatestBundle() {
        File root = new File(getFilesDir(), "deep-diagnostics");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) return;
        java.util.Arrays.sort(directories,
                Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            File report = new File(directory, "report.json");
            File bundle = new File(directory, "phase10-diagnostic-bundle.zip");
            if (report.isFile()) {
                try {
                    JSONObject json = new JSONObject(ResultFiles.readUtf8(report));
                    showReport(json);
                    if (bundle.isFile()) bundleFile = bundle;
                } catch (Exception ignored) {
                    // Continue to the next historical directory.
                }
                break;
            }
        }
        updateButtons();
    }

    private void showReport(JSONObject report) throws Exception {
        JSONObject comparison = report.optJSONObject("comparison");
        StringBuilder output = new StringBuilder();
        output.append(report.optString("report_id"))
                .append("\nModo: ").append(report.optString("mode"))
                .append("\nPerfil: ").append(report.optString("profile_sha256"));
        if (comparison != null) {
            output.append("\n\nVeredito: ").append(comparison.optString("verdict"))
                    .append("\nComparável: ").append(comparison.optBoolean("comparable", false))
                    .append("\nBloqueios: ").append(comparison.optJSONArray("blockers"))
                    .append("\nAvisos: ").append(comparison.optJSONArray("warnings"));
            JSONObject formats = comparison.optJSONObject("format_matrix");
            if (formats != null) {
                output.append("\n\nRegressões de formato: ")
                        .append(formats.optInt("regression_count", 0))
                        .append("\nGanhos de formato: ")
                        .append(formats.optInt("gain_count", 0));
            }
            JSONObject shaders = comparison.optJSONObject("shader_pipeline_corpus");
            if (shaders != null) {
                output.append(String.format(Locale.US,
                        "\nPipeline cold: %s%%\nPipeline warm: %s%%",
                        value(shaders.opt("cold_pipeline_change_percent")),
                        value(shaders.opt("warm_pipeline_change_percent"))));
            }
        }
        output.append("\n\n").append(Phase10Contract.LIMITATION);
        preview.setText(output.toString());
        status.setText("Fase 10 concluída.");
    }

    private static String value(Object value) {
        return value == null || value == JSONObject.NULL ? "n/d" : String.valueOf(value);
    }

    private DriverPackage selectedDriver() {
        int position = driverSpinner.getSelectedItemPosition();
        return position >= 0 && position < drivers.size() ? drivers.get(position) : null;
    }

    private void chooseExport() {
        if (bundleFile == null || !bundleFile.isFile()) {
            status.setText("Nenhum pacote da Fase 10 disponível.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "Amaral-Driver-Lab-Phase10-"
                + System.currentTimeMillis() + ".zip");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null
                || data.getData() == null || bundleFile == null) return;
        Uri target = data.getData();
        try (FileInputStream input = new FileInputStream(bundleFile);
             OutputStream output = getContentResolver().openOutputStream(target, "w")) {
            if (output == null) throw new IllegalStateException("Destino indisponível");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.flush();
            status.setText("Pacote exportado com sucesso.");
        } catch (Exception error) {
            status.setText("Falha ao exportar: " + error.getMessage());
        }
    }

    @Override public void onStatus(String message) {
        runOnUiThread(() -> {
            status.setText(message);
            updateButtons();
        });
    }

    @Override public void onComplete(File reportFile, File completedBundle, JSONObject report) {
        runOnUiThread(() -> {
            bundleFile = completedBundle;
            coordinator = null;
            try {
                showReport(report);
            } catch (Exception error) {
                preview.setText(report.toString());
            }
            updateButtons();
        });
    }

    @Override public void onFailure(String message, Throwable error) {
        runOnUiThread(() -> {
            coordinator = null;
            status.setText(message + ": " + error.getMessage());
            updateButtons();
        });
    }

    private void updateButtons() {
        boolean busy = coordinator != null && coordinator.isActive();
        boolean available = !drivers.isEmpty();
        if (fullButton != null) fullButton.setEnabled(!busy && available);
        if (soakButton != null) soakButton.setEnabled(!busy && available);
        if (exportButton != null) exportButton.setEnabled(!busy
                && bundleFile != null && bundleFile.isFile());
        if (driverSpinner != null) driverSpinner.setEnabled(!busy);
        if (cyclesInput != null) cyclesInput.setEnabled(!busy);
        if (memoryInput != null) memoryInput.setEnabled(!busy);
    }

    private int parse(EditText input, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(Integer.parseInt(
                    input.getText().toString().trim()), maximum));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText numeric(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1F);
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
}
