package com.amaral.driverlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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

public final class MainActivity extends Activity implements RunCoordinator.Listener {
    private static final int REQUEST_IMPORT_ZIP = 1001;
    private static final int REQUEST_EXPORT_JSON = 1002;
    private static final String PREFS = "driver_lab_settings";
    private static final String[] WORKLOAD_IDS = {
            WorkloadContract.RENDER_CORRECTNESS_ID,
            WorkloadContract.VISUAL_GEOMETRY_ID,
            WorkloadContract.VISUAL_MATERIALS_ID,
            WorkloadContract.VISUAL_POSTPROCESS_ID,
            WorkloadContract.TRACE_REPLAY_ID,
            WorkloadContract.SHADER_COMPILE_ID,
            WorkloadContract.RENDERPASS_TILING_ID,
            WorkloadContract.COMPUTE_ARITHMETIC_ID,
            WorkloadContract.STABLE_SCENE_ID,
            WorkloadContract.THERMAL_SUSTAIN_ID,
            WorkloadContract.TRANSFER_ID
    };

    private final List<DriverPackage> drivers = new ArrayList<>();
    private final List<View> controls = new ArrayList<>();

    private SharedPreferences preferences;
    private SecureTokenStore tokenStore;
    private Spinner driverSpinner;
    private Spinner workloadSpinner;
    private Spinner traceSpinner;
    private Spinner modeSpinner;
    private EditText warmupInput;
    private EditText durationInput;
    private EditText roundsInput;
    private EditText pixelToleranceInput;
    private EditText maximumDivergentBlocksInput;
    private EditText ownerInput;
    private EditText repositoryInput;
    private CheckBox autoIssueCheck;
    private TextView driverDetails;
    private TextView workloadNote;
    private TextView status;
    private TextView resultPreview;
    private Button githubButton;
    private JSONObject lastReport;
    private File lastReportFile;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        tokenStore = new SecureTokenStore(this);
        buildUi();
        loadDrivers();
        loadLastReport();
        updateGitHubButton();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(text("Amaral Driver Lab", 26, true));
        TextView subtitle = text(
                "Turnip/stock · correção primeiro · processo limpo · evidência reproduzível",
                14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, margins(0, 4, 0, 20));

        root.addView(text("1. Driver", 19, true), margins(0, 0, 0, 8));
        Button importButton = new Button(this);
        importButton.setText("IMPORTAR ZIP ADRENOTOOLS");
        importButton.setOnClickListener(view -> confirmImport());
        root.addView(importButton);
        controls.add(importButton);

        driverSpinner = new Spinner(this);
        root.addView(driverSpinner, margins(0, 8, 0, 4));
        controls.add(driverSpinner);
        driverDetails = text("Nenhum driver importado.", 13, false);
        driverDetails.setTextIsSelectable(true);
        root.addView(driverDetails, margins(0, 0, 0, 20));

        root.addView(text("2. Workload", 19, true), margins(0, 0, 0, 8));
        workloadSpinner = new Spinner(this);
        workloadSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        "Correção offscreen v1 · rápido",
                        "Cena visível: geometria e depth v1",
                        "Cena visível: materiais procedurais v1",
                        "Cena visível: pós-processamento v1",
                        "Trace replay Vulkan v1",
                        "Compilação de shaders v1",
                        "Render pass / tiling v1",
                        "Compute aritmético v1",
                        "Frametime estável v1",
                        "Sustentação térmica v1",
                        "Transferência fill/copy v1 · legado"
                }));
        workloadSpinner.setSelection(preferences.getInt("workload_position", 0));
        workloadSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("workload_position", position).apply();
                updateWorkloadControls();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(workloadSpinner);
        controls.add(workloadSpinner);

        workloadNote = text("", 13, false);
        workloadNote.setTextColor(Color.DKGRAY);
        root.addView(workloadNote, margins(0, 4, 0, 10));

        traceSpinner = new Spinner(this);
        traceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        TraceReplayContract.labelFor(TraceReplayContract.MIXED_TRACE_ID),
                        TraceReplayContract.labelFor(TraceReplayContract.COMPUTE_CHAIN_TRACE_ID)
                }));
        traceSpinner.setSelection(preferences.getInt("trace_position", 0));
        traceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("trace_position", position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(traceSpinner, margins(0, 0, 0, 10));
        controls.add(traceSpinner);

        LinearLayout correctionInputs = new LinearLayout(this);
        correctionInputs.setOrientation(LinearLayout.HORIZONTAL);
        pixelToleranceInput = numeric("Tolerância RGBA", String.valueOf(
                preferences.getInt("pixel_tolerance", WorkloadContract.DEFAULT_PIXEL_TOLERANCE)));
        maximumDivergentBlocksInput = numeric("Máx. blocos divergentes", String.valueOf(
                preferences.getInt("max_divergent_blocks",
                        WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS)));
        correctionInputs.addView(pixelToleranceInput, weighted());
        correctionInputs.addView(maximumDivergentBlocksInput, weighted());
        root.addView(correctionInputs, margins(0, 0, 0, 12));
        controls.add(pixelToleranceInput);
        controls.add(maximumDivergentBlocksInput);

        root.addView(text("3. Protocolo", 19, true), margins(0, 0, 0, 8));
        modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"A/B · sistema × candidato", "Somente candidato", "Somente sistema"}));
        modeSpinner.setSelection(0);
        root.addView(modeSpinner);
        controls.add(modeSpinner);

        LinearLayout timings = new LinearLayout(this);
        timings.setOrientation(LinearLayout.HORIZONTAL);
        warmupInput = numeric("Warm-up (s)", "3");
        durationInput = numeric("Medição (s)", "10");
        roundsInput = numeric("Rodadas", "5");
        timings.addView(warmupInput, weighted());
        timings.addView(durationInput, weighted());
        timings.addView(roundsInput, weighted());
        root.addView(timings, margins(0, 8, 0, 8));
        controls.add(warmupInput);
        controls.add(durationInput);
        controls.add(roundsInput);

        Button runButton = new Button(this);
        runButton.setText("▶ INICIAR TESTE");
        runButton.setOnClickListener(view -> startSuite());
        root.addView(runButton, margins(0, 4, 0, 8));
        controls.add(runButton);

        Button phase7Button = new Button(this);
        phase7Button.setText("★ TESTE FULL RECOMENDADO · FASE 11 / PERFIL v3");
        phase7Button.setOnClickListener(view ->
                startActivity(new Intent(this, QualificationActivity.class)));
        root.addView(phase7Button, margins(0, 0, 0, 8));
        controls.add(phase7Button);

        Button phase4Button = new Button(this);
        phase4Button.setText("HISTÓRICO · DIFF · RANKING · BISECT");
        phase4Button.setOnClickListener(view ->
                startActivity(new Intent(this, Phase4Activity.class)));
        root.addView(phase4Button, margins(0, 0, 0, 6));
        controls.add(phase4Button);

        Button phase6Button = new Button(this);
        phase6Button.setText("CAMPANHAS DE REGRESSÃO · FASE 6");
        phase6Button.setOnClickListener(view ->
                startActivity(new Intent(this, CampaignActivity.class)));
        root.addView(phase6Button, margins(0, 0, 0, 6));
        controls.add(phase6Button);

        Button phase9Button = new Button(this);
        phase9Button.setText("TELEMETRIA DE EMULADORES · FASE 9");
        phase9Button.setOnClickListener(view ->
                startActivity(new Intent(this, TelemetryActivity.class)));
        root.addView(phase9Button, margins(0, 0, 0, 6));
        controls.add(phase9Button);

        Button phase10Button = new Button(this);
        phase10Button.setText("DIAGNÓSTICO PROFUNDO TURNIP · FASE 10");
        phase10Button.setOnClickListener(view ->
                startActivity(new Intent(this, DeepDiagnosticsActivity.class)));
        root.addView(phase10Button, margins(0, 0, 0, 20));
        controls.add(phase10Button);

        root.addView(text("4. GitHub Issues", 19, true), margins(0, 0, 0, 8));
        ownerInput = textInput("Owner", preferences.getString("github_owner", "rickamaral94"));
        repositoryInput = textInput("Repositório",
                preferences.getString("github_repo", "Amaral-Driver-Lab"));
        root.addView(ownerInput);
        root.addView(repositoryInput, margins(0, 6, 0, 6));
        controls.add(ownerInput);
        controls.add(repositoryInput);

        autoIssueCheck = new CheckBox(this);
        autoIssueCheck.setText("Criar issue automaticamente ao concluir");
        autoIssueCheck.setChecked(tokenStore.load() != null);
        root.addView(autoIssueCheck);
        controls.add(autoIssueCheck);

        githubButton = new Button(this);
        githubButton.setOnClickListener(view -> handleGitHubConnection());
        root.addView(githubButton);
        controls.add(githubButton);

        Button sendButton = new Button(this);
        sendButton.setText("ENVIAR / ABRIR ÚLTIMA ISSUE");
        sendButton.setOnClickListener(view -> sendLastIssue());
        root.addView(sendButton, margins(0, 6, 0, 0));
        controls.add(sendButton);

        Button exportButton = new Button(this);
        exportButton.setText("EXPORTAR ÚLTIMO JSON");
        exportButton.setOnClickListener(view -> exportLastReport());
        root.addView(exportButton, margins(0, 6, 0, 20));
        controls.add(exportButton);

        root.addView(text("Status", 19, true));
        status = text("Pronto.", 14, false);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 6, 0, 12));
        resultPreview = text("", 12, false);
        resultPreview.setTextIsSelectable(true);
        resultPreview.setBackgroundColor(0xffeeeeee);
        resultPreview.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(resultPreview);

        setContentView(scroll);
        updateWorkloadControls();
    }

    private void updateWorkloadControls() {
        if (workloadSpinner == null || workloadNote == null) return;
        String workloadId = selectedWorkloadId();
        boolean correction = WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId);
        boolean visualScene = VisualSceneContract.isVisualScene(workloadId);
        boolean performance = WorkloadContract.isPerformance(workloadId);
        boolean traceReplay = WorkloadContract.TRACE_REPLAY_ID.equals(workloadId);
        workloadNote.setText(WorkloadContract.limitationFor(workloadId));
        warmupInput.setEnabled(!busy && performance);
        durationInput.setEnabled(!busy && performance);
        pixelToleranceInput.setEnabled(!busy && (correction || visualScene));
        maximumDivergentBlocksInput.setEnabled(!busy && (correction || visualScene));
        if (traceSpinner != null) traceSpinner.setEnabled(!busy && traceReplay);
    }

    private String selectedWorkloadId() {
        int position = workloadSpinner == null ? 0 : workloadSpinner.getSelectedItemPosition();
        if (position < 0 || position >= WORKLOAD_IDS.length) position = 0;
        return WORKLOAD_IDS[position];
    }

    private String selectedTraceId() {
        int position = traceSpinner == null ? 0 : traceSpinner.getSelectedItemPosition();
        return position == 1 ? TraceReplayContract.COMPUTE_CHAIN_TRACE_ID
                : TraceReplayContract.MIXED_TRACE_ID;
    }

    private void loadDrivers() {
        drivers.clear();
        File root = new File(getFilesDir(), "drivers");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                if (directory.getName().startsWith(".partial-")) continue;
                File descriptor = new File(directory, "descriptor.json");
                try {
                    DriverPackage driver = DriverPackage.fromJson(ResultFiles.readUtf8(descriptor));
                    if (driver.isUsable()) drivers.add(driver);
                } catch (Exception ignored) {
                    // Invalid/incomplete packages are intentionally not selectable.
                }
            }
        }
        drivers.sort(Comparator.comparing(DriverPackage::displayName));
        List<String> labels = new ArrayList<>();
        if (drivers.isEmpty()) labels.add("Nenhum driver importado");
        for (DriverPackage driver : drivers) labels.add(driver.displayName());
        driverSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        String selectedSha = preferences.getString("selected_driver_sha", "");
        int selectedIndex = 0;
        for (int index = 0; index < drivers.size(); ++index) {
            if (drivers.get(index).sha256.equals(selectedSha)) selectedIndex = index;
        }
        driverSpinner.setSelection(selectedIndex);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                DriverPackage selected = selectedDriver();
                showDriverDetails(selected);
                if (selected != null) {
                    preferences.edit().putString("selected_driver_sha", selected.sha256).apply();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        showDriverDetails(selectedDriver());
    }

    private DriverPackage selectedDriver() {
        if (drivers.isEmpty()) return null;
        int position = driverSpinner.getSelectedItemPosition();
        return position >= 0 && position < drivers.size() ? drivers.get(position) : drivers.get(0);
    }

    private void showDriverDetails(DriverPackage selected) {
        if (selected == null) {
            driverDetails.setText("Nenhum driver importado.");
        } else {
            driverDetails.setText("Biblioteca: " + selected.libraryName
                    + "\nSHA-256: " + selected.sha256);
        }
    }

    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle("Importar código nativo")
                .setMessage("O APK executará as bibliotecas .so deste ZIP. Importe apenas drivers "
                        + "que você compilou ou verificou; o SHA-256 será registrado em cada teste.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Selecionar ZIP", (dialog, which) -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("application/zip");
                    startActivityForResult(picker, REQUEST_IMPORT_ZIP);
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_IMPORT_ZIP) {
            Uri uri = data.getData();
            setBusy(true);
            status.setText("Validando meta.json, SHA-256 e bibliotecas…");
            new Thread(() -> {
                try {
                    DriverPackage imported = DriverImporter.importZip(this, uri);
                    runOnUiThread(() -> {
                        preferences.edit().putString("selected_driver_sha", imported.sha256).apply();
                        loadDrivers();
                        selectDriverBySha(imported.sha256);
                        status.setText("Importado: " + imported.displayName());
                        setBusy(false);
                    });
                } catch (Throwable error) {
                    runOnUiThread(() -> {
                        status.setText("Importação recusada: " + error.getMessage());
                        setBusy(false);
                    });
                }
            }, "driver-import").start();
        } else if (requestCode == REQUEST_EXPORT_JSON) {
            exportToUri(data.getData());
        }
    }

    private void selectDriverBySha(String sha) {
        for (int index = 0; index < drivers.size(); ++index) {
            if (drivers.get(index).sha256.equals(sha)) {
                driverSpinner.setSelection(index);
                return;
            }
        }
    }

    private void startSuite() {
        try {
            int mode = modeSpinner.getSelectedItemPosition() == 0 ? RunCoordinator.MODE_AB
                    : modeSpinner.getSelectedItemPosition() == 1 ? RunCoordinator.MODE_CUSTOM
                    : RunCoordinator.MODE_SYSTEM;
            String workloadId = selectedWorkloadId();
            int warmup = parseNumber(warmupInput, "Warm-up", 0, 30);
            int duration = parseNumber(durationInput, "Medição", 1, 120);
            int rounds = parseNumber(roundsInput, "Rodadas", 1, 10);
            int tolerance = parseNumber(pixelToleranceInput, "Tolerância RGBA", 0, 255);
            int maximumDivergent = parseNumber(
                    maximumDivergentBlocksInput, "Máximo de blocos divergentes", 0, 256);
            preferences.edit()
                    .putInt("pixel_tolerance", tolerance)
                    .putInt("max_divergent_blocks", maximumDivergent)
                    .apply();
            saveGitHubTarget();
            setBusy(true);
            resultPreview.setText("");
            new RunCoordinator(this, selectedDriver(), mode, rounds, warmup, duration,
                    workloadId, selectedTraceId(), tolerance, maximumDivergent, this).start();
        } catch (Throwable error) {
            status.setText(error.getMessage());
            setBusy(false);
        }
    }

    @Override
    public void onStatus(String message) {
        status.setText(message);
    }

    @Override
    public void onComplete(File reportFile, JSONObject report) {
        lastReportFile = reportFile;
        lastReport = report;
        preferences.edit().putString("last_report_path", reportFile.getAbsolutePath()).apply();
        setBusy(false);
        showSummary(report);
        if (autoIssueCheck.isChecked()) {
            String token = tokenStore.load();
            if (token == null) {
                status.append("\nResultado salvo; conecte o GitHub para envio automático.");
            } else {
                publishIssue(token);
            }
        }
    }

    @Override
    public void onFailure(String message, Throwable error) {
        status.setText(message + ": " + error.getMessage());
        setBusy(false);
    }

    private void showSummary(JSONObject report) {
        String workloadId = report.optString("workload_id", WorkloadContract.TRANSFER_ID);
        String verdict = report.optString("verdict", "completed");
        JSONObject summary = report.optJSONObject("summary");
        StringBuilder headline = new StringBuilder("Suíte concluída");
        if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
            if ("passed_render_correctness".equals(verdict)) {
                headline.append(" · CORREÇÃO APROVADA");
            } else if ("failed_render_correctness".equals(verdict)) {
                headline.append(" · CORREÇÃO REPROVADA");
            } else if ("failed_execution".equals(verdict)) {
                headline.append(" · FALHA DE EXECUÇÃO");
            } else {
                headline.append(" · SEM REFERÊNCIA A/B");
            }
            if (summary != null) {
                double match = summary.optDouble("pixel_match_percent", Double.NaN);
                if (Double.isFinite(match)) {
                    headline.append(String.format(Locale.US, " · pixels %.4f%%", match));
                }
                Object blocks = summary.opt("maximum_divergent_block_count");
                if (blocks instanceof Number) {
                    headline.append(" · blocos divergentes ").append(((Number) blocks).intValue());
                }
            }
            JSONObject capabilityDiff = report.optJSONObject("capability_diff");
            if (capabilityDiff != null) {
                JSONArray gained = capabilityDiff.optJSONArray("extensions_gained");
                JSONArray lost = capabilityDiff.optJSONArray("extensions_lost");
                headline.append("\nExtensões: +")
                        .append(gained == null ? 0 : gained.length())
                        .append(" / -")
                        .append(lost == null ? 0 : lost.length());
            }
            JSONArray failures = report.optJSONArray("failure_catalog");
            if (failures != null && failures.length() > 0) {
                headline.append(" · eventos de falha ").append(failures.length());
            }
        } else if (VisualSceneContract.isVisualScene(workloadId)) {
            JSONObject visual = report.optJSONObject("visual_scene");
            if (visual != null) {
                if (visual.optBoolean("passed_correctness_gate", false)) {
                    headline.append(" · CHECKPOINTS APROVADOS");
                } else if (visual.optBoolean("comparison_available", false)) {
                    headline.append(" · DIVERGÊNCIA VISUAL");
                } else {
                    headline.append(" · SEM REFERÊNCIA A/B");
                }
                double match = visual.optDouble("minimum_pixel_match_percent", Double.NaN);
                if (Double.isFinite(match)) {
                    headline.append(String.format(Locale.US, " · pixels mín. %.4f%%", match));
                }
                headline.append(" · mismatches ")
                        .append(visual.optInt("checkpoint_mismatch_count", 0));
            }
            JSONObject analysis = report.optJSONObject("statistical_analysis");
            if (analysis != null && analysis.optBoolean("available", false)) {
                double improvement = analysis.optDouble(
                        "median_paired_improvement_percent", Double.NaN);
                if (Double.isFinite(improvement)) {
                    headline.append(String.format(Locale.US,
                            " · melhora P99 mediana %+.2f%%", improvement));
                }
                headline.append(" · ").append(analysis.optString(
                        "classification", "inconclusive"));
            }
        } else if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
            JSONObject trace = report.optJSONObject("trace_replay");
            if (trace != null) {
                if (trace.optBoolean("passed_correctness_gate", false)) {
                    headline.append(" · SAÍDA REPRODUZÍVEL");
                } else if (trace.optBoolean("comparison_available", false)) {
                    headline.append(" · DIVERGÊNCIA DE SAÍDA");
                } else {
                    headline.append(" · SEM REFERÊNCIA A/B");
                }
                headline.append(" · pares ").append(trace.optInt("complete_pair_count", 0));
                headline.append(" · mismatches ").append(trace.optInt("output_mismatch_count", 0));
            }
            JSONObject analysis = report.optJSONObject("statistical_analysis");
            if (analysis != null && analysis.optBoolean("available", false)) {
                double improvement = analysis.optDouble(
                        "median_paired_improvement_percent", Double.NaN);
                if (Double.isFinite(improvement)) {
                    headline.append(String.format(Locale.US,
                            " · melhora mediana %+.2f%%", improvement));
                }
                headline.append(" · ").append(analysis.optString(
                        "classification", "inconclusive"));
            }
        } else {
            JSONObject analysis = report.optJSONObject("statistical_analysis");
            if (analysis != null && analysis.optBoolean("available", false)) {
                double improvement = analysis.optDouble(
                        "median_paired_improvement_percent", Double.NaN);
                JSONObject interval = analysis.optJSONObject(
                        "confidence_interval_95_percent");
                if (Double.isFinite(improvement)) {
                    headline.append(String.format(Locale.US,
                            " · melhora mediana %+.2f%%", improvement));
                }
                if (interval != null) {
                    double lower = interval.optDouble("lower", Double.NaN);
                    double upper = interval.optDouble("upper", Double.NaN);
                    if (Double.isFinite(lower) && Double.isFinite(upper)) {
                        headline.append(String.format(Locale.US,
                                " · IC95%% [%+.2f, %+.2f]", lower, upper));
                    }
                }
                headline.append(" · ").append(analysis.optString(
                        "classification", "inconclusive"));
            } else {
                double delta = summary == null ? Double.NaN
                        : summary.optDouble("candidate_vs_system_percent", Double.NaN);
                if (Double.isFinite(delta)) {
                    headline.append(String.format(Locale.US,
                            " · candidato %+.2f%%", delta));
                }
            }
        }
        headline.append("\n").append(WorkloadContract.limitationFor(workloadId));
        if (lastReportFile != null) headline.append("\n").append(lastReportFile.getAbsolutePath());
        status.setText(headline.toString());

        String preview;
        try {
            preview = report.toString(2);
        } catch (Exception ignored) {
            preview = report.toString();
        }
        if (preview.length() > 12_000) preview = preview.substring(0, 12_000) + "\n…";
        resultPreview.setText(preview);
    }

    private void handleGitHubConnection() {
        if (tokenStore.load() != null) {
            new AlertDialog.Builder(this)
                    .setTitle("GitHub conectado")
                    .setMessage("O token está protegido pelo Android Keystore.")
                    .setNegativeButton("Manter", null)
                    .setPositiveButton("Desconectar", (dialog, which) -> {
                        tokenStore.clear();
                        autoIssueCheck.setChecked(false);
                        updateGitHubButton();
                    })
                    .show();
            return;
        }
        if (BuildConfig.GITHUB_CLIENT_ID.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Build sem GitHub App")
                    .setMessage("Configure GITHUB_CLIENT_ID no build. Até lá, o botão de issue "
                            + "abre um rascunho no navegador sem armazenar credenciais.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        status.setText("Solicitando código de acesso ao GitHub…");
        GitHubDeviceFlow.authorize(BuildConfig.GITHUB_CLIENT_ID, new GitHubDeviceFlow.Callback() {
            @Override public void onCode(String userCode, String verificationUri) {
                runOnUiThread(() -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", userCode));
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Autorizar GitHub")
                            .setMessage("Código copiado: " + userCode
                                    + "\n\nA permissão deve ficar limitada ao repositório de resultados "
                                    + "e a Issues: write.")
                            .setNegativeButton("Fechar", null)
                            .setPositiveButton("Abrir GitHub", (dialog, which) ->
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(verificationUri))))
                            .show();
                    status.setText("Aguardando autorização do código " + userCode + "…");
                });
            }

            @Override public void onAuthorized(String token) {
                try {
                    tokenStore.save(token);
                    runOnUiThread(() -> {
                        autoIssueCheck.setChecked(true);
                        updateGitHubButton();
                        status.setText("GitHub conectado com sucesso.");
                    });
                } catch (Throwable error) {
                    onError(error);
                }
            }

            @Override public void onError(Throwable error) {
                runOnUiThread(() -> status.setText("GitHub: " + error.getMessage()));
            }
        });
    }

    private void sendLastIssue() {
        if (lastReport == null) {
            status.setText("Ainda não há resultado para enviar.");
            return;
        }
        saveGitHubTarget();
        String token = tokenStore.load();
        if (token != null) {
            publishIssue(token);
            return;
        }
        try {
            GitHubIssuePublisher.openDraft(this,
                    ownerInput.getText().toString().trim(),
                    repositoryInput.getText().toString().trim(), lastReport);
        } catch (Throwable error) {
            status.setText("Não foi possível abrir o rascunho: " + error.getMessage());
        }
    }

    private void publishIssue(String token) {
        status.setText("Criando issue no GitHub…");
        setBusy(true);
        String owner = ownerInput.getText().toString().trim();
        String repository = repositoryInput.getText().toString().trim();
        new Thread(() -> {
            try {
                String issueUrl = GitHubIssuePublisher.publish(token, owner, repository, lastReport);
                runOnUiThread(() -> {
                    status.setText("Issue criada: " + issueUrl);
                    setBusy(false);
                    Toast.makeText(this, "Issue criada", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    status.setText("Resultado salvo, mas o envio falhou: " + error.getMessage());
                    setBusy(false);
                });
            }
        }, "github-issue").start();
    }

    private void exportLastReport() {
        if (lastReportFile == null || !lastReportFile.isFile()) {
            status.setText("Ainda não há JSON para exportar.");
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/json");
        create.putExtra(Intent.EXTRA_TITLE, lastReportFile.getParentFile().getName() + ".json");
        startActivityForResult(create, REQUEST_EXPORT_JSON);
    }

    private void exportToUri(Uri destination) {
        try (InputStream input = new FileInputStream(lastReportFile);
             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("Destino indisponível");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            status.setText("JSON exportado.");
        } catch (Throwable error) {
            status.setText("Exportação falhou: " + error.getMessage());
        }
    }

    private void loadLastReport() {
        String path = preferences.getString("last_report_path", "");
        if (path.isEmpty()) return;
        try {
            File file = new File(path);
            if (!ResultFiles.isInside(new File(getFilesDir(), "runs"), file) || !file.isFile()) return;
            lastReportFile = file;
            lastReport = new JSONObject(ResultFiles.readUtf8(file));
            showSummary(lastReport);
        } catch (Exception ignored) {
            // A stale report reference is non-fatal.
        }
    }

    private void saveGitHubTarget() {
        preferences.edit()
                .putString("github_owner", ownerInput.getText().toString().trim())
                .putString("github_repo", repositoryInput.getText().toString().trim())
                .apply();
    }

    private void updateGitHubButton() {
        githubButton.setText(tokenStore.load() == null ? "CONECTAR GITHUB" : "GITHUB CONECTADO");
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        for (View control : controls) control.setEnabled(!busy);
        updateWorkloadControls();
    }

    private int parseNumber(EditText input, String label, int minimum, int maximum) {
        int value;
        try {
            value = Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " inválido");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " deve ficar entre " + minimum + " e " + maximum);
        }
        return value;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText numeric(String hint, String value) {
        EditText input = textInput(hint, value);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private EditText textInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        return input;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(6));
        return params;
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
