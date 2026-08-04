package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class TelemetryActivity extends Activity {
    private static final int REQUEST_IMPORT = 7101;
    private static final int REQUEST_EXPORT_REPORT = 7102;
    private static final int REQUEST_EXPORT_BUNDLE = 7103;

    private final List<DriverPackage> drivers = new ArrayList<>();
    private final List<SessionItem> sessions = new ArrayList<>();
    private final List<View> controls = new ArrayList<>();

    private Spinner driverSpinner;
    private Spinner sessionSpinner;
    private TextView bindingDetails;
    private TextView status;
    private TextView preview;
    private File selectedReportFile;
    private File selectedBundleFile;
    private JSONObject selectedReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadDrivers();
        loadSessions(null);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root);

        root.addView(text("Telemetria de emuladores · Fase 7", 24, true));
        TextView subtitle = text(
                "Contrato aberto v1 · opt-in · armazenamento local · sem upload automático",
                14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, margins(0, 4, 0, 18));

        root.addView(text("1. Vínculo esperado do driver", 18, true));
        driverSpinner = new Spinner(this);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateBindingDetails();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(driverSpinner, margins(0, 6, 0, 4));
        controls.add(driverSpinner);
        bindingDetails = text("", 13, false);
        bindingDetails.setTextIsSelectable(true);
        root.addView(bindingDetails, margins(0, 0, 0, 14));

        TextView note = text(
                "A importação só é aceita quando o modo e o SHA-256 declarados pelo produtor "
                        + "coincidem com esta seleção. Isso evita atribuição acidental, mas não prova "
                        + "que o emulador carregou o driver durante toda a sessão.",
                13, false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(0, 0, 0, 14));

        Button importButton = new Button(this);
        importButton.setText("IMPORTAR TELEMETRIA JSON V1");
        importButton.setOnClickListener(view -> chooseImport());
        root.addView(importButton, margins(0, 0, 0, 18));
        controls.add(importButton);

        root.addView(text("2. Sessões locais", 18, true));
        sessionSpinner = new Spinner(this);
        sessionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectSession(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(sessionSpinner, margins(0, 6, 0, 8));
        controls.add(sessionSpinner);

        Button exportReport = new Button(this);
        exportReport.setText("EXPORTAR RELATÓRIO ANALISADO");
        exportReport.setOnClickListener(view -> chooseExport(false));
        root.addView(exportReport, margins(0, 0, 0, 6));
        controls.add(exportReport);

        Button exportBundle = new Button(this);
        exportBundle.setText("EXPORTAR BUNDLE ORIGINAL");
        exportBundle.setOnClickListener(view -> chooseExport(true));
        root.addView(exportBundle, margins(0, 0, 0, 18));
        controls.add(exportBundle);

        root.addView(text("Status", 18, true));
        status = text("Pronto para importar.", 14, false);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 6, 0, 10));

        preview = text("", 12, false);
        preview.setTextIsSelectable(true);
        preview.setBackgroundColor(0xffeeeeee);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(preview);

        setContentView(scroll);
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
                    // Invalid packages are not offered as telemetry evidence.
                }
            }
        }
        drivers.sort(Comparator.comparing(DriverPackage::displayName));
        List<String> labels = new ArrayList<>();
        labels.add("Driver do sistema");
        for (DriverPackage driver : drivers) labels.add(driver.displayName());
        driverSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        updateBindingDetails();
    }

    private DriverPackage selectedDriver() {
        int position = driverSpinner.getSelectedItemPosition();
        return position <= 0 || position - 1 >= drivers.size() ? null : drivers.get(position - 1);
    }

    private void updateBindingDetails() {
        if (bindingDetails == null) return;
        DriverPackage selected = selectedDriver();
        bindingDetails.setText(selected == null
                ? "Esperado: mode=system e candidate_sha256=null"
                : "Esperado: mode=custom\nSHA-256: " + selected.sha256);
    }

    private void chooseImport() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/json");
        startActivityForResult(picker, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT) {
            importTelemetry(uri);
        } else if (requestCode == REQUEST_EXPORT_REPORT) {
            copyToUri(selectedReportFile, uri, "Relatório exportado.");
        } else if (requestCode == REQUEST_EXPORT_BUNDLE) {
            copyToUri(selectedBundleFile, uri, "Bundle original exportado.");
        }
    }

    private void importTelemetry(Uri uri) {
        setBusy(true);
        status.setText("Validando schema, privacidade, ordem dos eventos e vínculo do driver…");
        DriverPackage expected = selectedDriver();
        new Thread(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                TelemetryImporter.ImportResult result =
                        TelemetryImporter.importBundle(this, input, expected);
                runOnUiThread(() -> {
                    loadSessions(result.report.optString("session_id"));
                    status.setText(result.alreadyImported
                            ? "Sessão já estava importada; hash idêntico confirmado."
                            : "Telemetria importada e analisada localmente.");
                    setBusy(false);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    status.setText("Importação recusada: " + error.getMessage());
                    setBusy(false);
                });
            }
        }, "telemetry-import").start();
    }

    private void loadSessions(String preferredSessionId) {
        sessions.clear();
        File root = new File(getFilesDir(), TelemetryImporter.ROOT_DIRECTORY);
        File[] directories = root.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                if (directory.getName().startsWith(".partial-")) continue;
                File reportFile = new File(directory, TelemetryImporter.REPORT_FILE);
                File bundleFile = new File(directory, TelemetryImporter.BUNDLE_FILE);
                try {
                    if (!reportFile.isFile() || !bundleFile.isFile()) continue;
                    JSONObject report = new JSONObject(ResultFiles.readUtf8(reportFile));
                    sessions.add(new SessionItem(reportFile, bundleFile, report));
                } catch (Exception ignored) {
                    // Corrupt/incomplete sessions stay hidden instead of being summarized.
                }
            }
        }
        sessions.sort((left, right) -> Long.compare(
                right.report.optLong("imported_at_unix_ms", 0L),
                left.report.optLong("imported_at_unix_ms", 0L)));
        List<String> labels = new ArrayList<>();
        if (sessions.isEmpty()) labels.add("Nenhuma sessão importada");
        for (SessionItem item : sessions) labels.add(item.label());
        sessionSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        int selected = 0;
        if (preferredSessionId != null) {
            for (int index = 0; index < sessions.size(); ++index) {
                if (preferredSessionId.equals(sessions.get(index).report.optString("session_id"))) {
                    selected = index;
                    break;
                }
            }
        }
        sessionSpinner.setSelection(selected);
        selectSession(selected);
    }

    private void selectSession(int position) {
        if (sessions.isEmpty() || position < 0 || position >= sessions.size()) {
            selectedReportFile = null;
            selectedBundleFile = null;
            selectedReport = null;
            preview.setText("Nenhuma telemetria local.");
            return;
        }
        SessionItem item = sessions.get(position);
        selectedReportFile = item.reportFile;
        selectedBundleFile = item.bundleFile;
        selectedReport = item.report;
        showReport(item.report);
    }

    private void showReport(JSONObject report) {
        JSONObject session = report.optJSONObject("session");
        JSONObject analysis = report.optJSONObject("analysis");
        StringBuilder summary = new StringBuilder();
        if (session != null) {
            JSONObject emulator = session.optJSONObject("emulator");
            JSONObject content = session.optJSONObject("content");
            JSONObject driver = session.optJSONObject("driver_binding");
            summary.append(emulator == null ? "Emulador desconhecido"
                    : emulator.optString("name") + " " + emulator.optString("version"));
            if (content != null) {
                String hash = content.optString("content_id_hash");
                summary.append("\nConteúdo: ").append(content.optString("platform", "unknown"))
                        .append(" · ").append(prefix(hash));
            }
            if (driver != null) {
                summary.append("\nDriver: ").append(driver.optString("mode", "unknown"));
                if ("custom".equals(driver.optString("mode"))) {
                    summary.append(" · ").append(prefix(driver.optString("candidate_sha256")));
                }
            }
        }
        if (analysis != null) {
            summary.append("\nFrames: ").append(analysis.optInt("frame_count", 0));
            double fps = analysis.optDouble("estimated_average_fps", Double.NaN);
            if (Double.isFinite(fps)) summary.append(String.format(Locale.US, " · FPS est. %.2f", fps));
            JSONObject frameTime = analysis.optJSONObject("frame_time_ms");
            if (frameTime != null) {
                summary.append(String.format(Locale.US, " · p95 %.2f ms · p99 %.2f ms",
                        frameTime.optDouble("p95"), frameTime.optDouble("p99")));
            }
            summary.append("\nErros Vulkan: ").append(analysis.optInt("vulkan_error_count", 0))
                    .append(" · avisos render: ").append(analysis.optInt("render_warning_count", 0))
                    .append(" · crashes: ").append(analysis.optInt("crash_count", 0))
                    .append(" · hangs: ").append(analysis.optInt("hang_count", 0));
            JSONArray warnings = analysis.optJSONArray("validity_warnings");
            summary.append("\nAvisos de validade: ").append(warnings == null ? 0 : warnings.length());
        }
        summary.append("\n\n").append(Phase7Contract.LIMITATION);
        status.setText(summary.toString());
        String encoded;
        try {
            encoded = report.toString(2);
        } catch (Exception ignored) {
            encoded = report.toString();
        }
        if (encoded.length() > 16_000) encoded = encoded.substring(0, 16_000) + "\n…";
        preview.setText(encoded);
    }

    private void chooseExport(boolean bundle) {
        File source = bundle ? selectedBundleFile : selectedReportFile;
        if (source == null || !source.isFile()) {
            status.setText("Nenhuma sessão selecionada para exportar.");
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/json");
        String id = selectedReport == null ? "telemetry" : selectedReport.optString("session_id", "telemetry");
        create.putExtra(Intent.EXTRA_TITLE,
                id + (bundle ? "-bundle.json" : "-report.json"));
        startActivityForResult(create, bundle ? REQUEST_EXPORT_BUNDLE : REQUEST_EXPORT_REPORT);
    }

    private void copyToUri(File source, Uri destination, String success) {
        if (source == null || !source.isFile()) {
            status.setText("Arquivo de origem indisponível.");
            return;
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("destino indisponível");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            status.setText(success);
        } catch (Throwable error) {
            status.setText("Exportação falhou: " + error.getMessage());
        }
    }

    private void setBusy(boolean value) {
        for (View control : controls) control.setEnabled(!value);
    }

    private String prefix(String hash) {
        return hash == null || hash.length() < 12 ? "unknown" : hash.substring(0, 12);
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

    private static final class SessionItem {
        final File reportFile;
        final File bundleFile;
        final JSONObject report;

        SessionItem(File reportFile, File bundleFile, JSONObject report) {
            this.reportFile = reportFile;
            this.bundleFile = bundleFile;
            this.report = report;
        }

        String label() {
            JSONObject session = report.optJSONObject("session");
            JSONObject emulator = session == null ? null : session.optJSONObject("emulator");
            String name = emulator == null ? "Emulador" : emulator.optString("name", "Emulador");
            String id = report.optString("session_id", "unknown");
            return name + " · " + (id.length() > 8 ? id.substring(0, 8) : id);
        }
    }
}
