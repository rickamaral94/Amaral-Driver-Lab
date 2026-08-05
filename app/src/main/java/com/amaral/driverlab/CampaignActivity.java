package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CampaignActivity extends LocalizedActivity implements CampaignCoordinator.Listener {
    private static final int REQUEST_EXPORT_CAMPAIGN = 6001;

    private static final class WorkloadOption {
        final CampaignWorkload workload;
        final boolean defaultSelected;
        CheckBox checkBox;

        WorkloadOption(CampaignWorkload workload, boolean defaultSelected) {
            this.workload = workload;
            this.defaultSelected = defaultSelected;
        }
    }

    private final List<DriverPackage> drivers = new ArrayList<>();
    private final List<CheckBox> driverChecks = new ArrayList<>();
    private final List<WorkloadOption> workloadOptions = new ArrayList<>();
    private final List<View> editableControls = new ArrayList<>();

    private LinearLayout driverContainer;
    private LinearLayout workloadContainer;
    private EditText roundsInput;
    private EditText warmupInput;
    private EditText measureInput;
    private EditText cooldownInput;
    private TextView status;
    private TextView preview;
    private Button startButton;
    private Button resumeButton;
    private Button pauseButton;
    private Button exportButton;
    private CampaignCoordinator coordinator;
    private File currentCampaignFile;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        defineWorkloads();
        buildUi();
        loadDrivers();
        findLatestCampaign();
    }

    private void defineWorkloads() {
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.RENDER_CORRECTNESS_ID, ""), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.VISUAL_GEOMETRY_ID, ""), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.VISUAL_MATERIALS_ID, ""), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.VISUAL_POSTPROCESS_ID, ""), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.MIXED_TRACE_ID), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.TRACE_REPLAY_ID,
                TraceReplayContract.COMPUTE_CHAIN_TRACE_ID), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.SHADER_COMPILE_ID, ""), false));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.RENDERPASS_TILING_ID, ""), false));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.COMPUTE_ARITHMETIC_ID, ""), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.STABLE_SCENE_ID, ""), true));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.THERMAL_SUSTAIN_ID, ""), false));
        workloadOptions.add(new WorkloadOption(new CampaignWorkload(
                WorkloadContract.TRANSFER_ID, ""), false));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AmaralColors.BACKGROUND);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Fase 6 · Campanhas de regressão", 24, true));
        TextView note = text(Phase6Contract.LIMITATION, 13, false);
        note.setTextColor(AmaralColors.TEXT_SECONDARY);
        root.addView(note, margins(0, 4, 0, 16));

        root.addView(text("1. Drivers candidatos", 18, true));
        driverContainer = new LinearLayout(this);
        driverContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(driverContainer, margins(0, 6, 0, 16));

        root.addView(text("2. Workloads e traces", 18, true));
        workloadContainer = new LinearLayout(this);
        workloadContainer.setOrientation(LinearLayout.VERTICAL);
        for (WorkloadOption option : workloadOptions) {
            CheckBox check = new CheckBox(this);
            check.setText(option.workload.label());
            check.setChecked(option.defaultSelected);
            option.checkBox = check;
            workloadContainer.addView(check);
            editableControls.add(check);
        }
        root.addView(workloadContainer, margins(0, 6, 0, 16));

        root.addView(text("3. Protocolo A/B", 18, true));
        LinearLayout protocol = new LinearLayout(this);
        protocol.setOrientation(LinearLayout.HORIZONTAL);
        roundsInput = numeric("Rodadas", "5");
        warmupInput = numeric("Warm-up (s)", "3");
        measureInput = numeric("Medição (s)", "10");
        cooldownInput = numeric("Cooldown (s)", "15");
        protocol.addView(roundsInput, weighted());
        protocol.addView(warmupInput, weighted());
        protocol.addView(measureInput, weighted());
        protocol.addView(cooldownInput, weighted());
        root.addView(protocol, margins(0, 6, 0, 12));
        editableControls.add(roundsInput);
        editableControls.add(warmupInput);
        editableControls.add(measureInput);
        editableControls.add(cooldownInput);

        startButton = button("CRIAR E INICIAR CAMPANHA", view -> createCampaign());
        resumeButton = button("RETOMAR CAMPANHA INCOMPLETA", view -> resumeCampaign());
        pauseButton = button("PAUSAR APÓS O JOB ATUAL", view -> pauseCampaign());
        exportButton = button("EXPORTAR CAMPAIGN.JSON", view -> chooseExport());
        Button refreshButton = button("ATUALIZAR MANIFESTO", view -> refreshPreview());
        root.addView(startButton);
        root.addView(resumeButton, margins(0, 4, 0, 0));
        root.addView(pauseButton, margins(0, 4, 0, 0));
        root.addView(refreshButton, margins(0, 4, 0, 0));
        root.addView(exportButton, margins(0, 4, 0, 16));
        editableControls.add(startButton);

        status = text("Nenhuma campanha carregada.", 14, true);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 0, 0, 10));
        preview = text("", 12, false);
        preview.setTextIsSelectable(true);
        root.addView(preview);
        setContentView(scroll);
        updateButtons();
    }

    private void loadDrivers() {
        drivers.clear();
        driverChecks.clear();
        driverContainer.removeAllViews();
        File root = new File(getFilesDir(), "drivers");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                if (directory.getName().startsWith(".partial-")) continue;
                try {
                    DriverPackage driver = DriverPackage.fromJson(ResultFiles.readUtf8(
                            new File(directory, "descriptor.json")));
                    if (driver.isUsable()) drivers.add(driver);
                } catch (Exception ignored) {
                    // Incomplete drivers are excluded.
                }
            }
        }
        drivers.sort(Comparator.comparing(DriverPackage::displayName));
        if (drivers.isEmpty()) {
            driverContainer.addView(text("Importe drivers na tela principal primeiro.", 13, false));
            updateButtons();
            return;
        }
        for (int index = 0; index < drivers.size(); ++index) {
            DriverPackage driver = drivers.get(index);
            CheckBox check = new CheckBox(this);
            check.setText(driver.displayName() + "\nSHA-256 " + driver.sha256);
            check.setChecked(index < Phase6Contract.MAX_DRIVERS);
            driverChecks.add(check);
            driverContainer.addView(check);
            editableControls.add(check);
        }
        updateButtons();
    }

    private void createCampaign() {
        try {
            JSONArray selectedDrivers = selectedDrivers();
            JSONArray selectedWorkloads = selectedWorkloads();
            int rounds = parse(roundsInput, "Rodadas", 1, 10);
            int warmup = parse(warmupInput, "Warm-up", 0, 30);
            int measure = parse(measureInput, "Medição", 1, 120);
            int cooldown = parse(cooldownInput, "Cooldown", 0,
                    Phase6Contract.MAX_COOLDOWN_SECONDS);
            JSONObject protocol = new JSONObject()
                    .put("mode", "ab_system_vs_candidate")
                    .put("rounds", rounds)
                    .put("warmup_seconds", warmup)
                    .put("measure_seconds", measure)
                    .put("cooldown_seconds", cooldown)
                    .put("pixel_tolerance", WorkloadContract.DEFAULT_PIXEL_TOLERANCE)
                    .put("maximum_divergent_blocks",
                            WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS)
                    .put("within_suite_order_policy", "AB/BA alternating");
            long now = System.currentTimeMillis();
            JSONObject campaign = CampaignPlan.create("campaign-" + now, now,
                    selectedDrivers, selectedWorkloads, protocol);
            currentCampaignFile = CampaignStore.create(getFilesDir(), campaign);
            beginCoordinator();
        } catch (Throwable error) {
            status.setText(error.getMessage());
        }
    }

    private JSONArray selectedDrivers() throws Exception {
        JSONArray output = new JSONArray();
        for (int index = 0; index < driverChecks.size(); ++index) {
            if (driverChecks.get(index).isChecked()) {
                output.put(CampaignPlan.driverRef(drivers.get(index)));
            }
        }
        if (output.length() < 1 || output.length() > Phase6Contract.MAX_DRIVERS) {
            throw new IllegalArgumentException("Selecione de 1 a "
                    + Phase6Contract.MAX_DRIVERS + " drivers");
        }
        return output;
    }

    private JSONArray selectedWorkloads() throws Exception {
        JSONArray output = new JSONArray();
        for (WorkloadOption option : workloadOptions) {
            if (option.checkBox.isChecked()) output.put(option.workload.toJson());
        }
        if (output.length() < 1 || output.length() > Phase6Contract.MAX_WORKLOAD_SPECS) {
            throw new IllegalArgumentException("Selecione de 1 a "
                    + Phase6Contract.MAX_WORKLOAD_SPECS + " workloads/traces");
        }
        return output;
    }

    private void findLatestCampaign() {
        File latest = CampaignStore.findLatestIncomplete(getFilesDir());
        if (latest != null) currentCampaignFile = latest;
        refreshPreview();
    }

    private void resumeCampaign() {
        if (currentCampaignFile == null || !currentCampaignFile.isFile()) {
            currentCampaignFile = CampaignStore.findLatestIncomplete(getFilesDir());
        }
        if (currentCampaignFile == null) {
            status.setText("Nenhuma campanha incompleta encontrada.");
            return;
        }
        beginCoordinator();
    }

    private void beginCoordinator() {
        if (currentCampaignFile == null) return;
        setBusy(true);
        coordinator = new CampaignCoordinator(this, currentCampaignFile, this);
        coordinator.startOrResume();
        updateButtons();
    }

    private void pauseCampaign() {
        if (coordinator == null || !coordinator.isActive()) {
            status.setText("Nenhuma campanha está em execução.");
            return;
        }
        coordinator.pauseAfterCurrent();
    }

    private void refreshPreview() {
        if (currentCampaignFile == null || !currentCampaignFile.isFile()) {
            updateButtons();
            return;
        }
        try {
            JSONObject campaign = CampaignStore.load(currentCampaignFile);
            showCampaign(campaign);
        } catch (Throwable error) {
            status.setText("Falha ao ler campaign.json: " + error.getMessage());
        }
        updateButtons();
    }

    private void showCampaign(JSONObject campaign) throws Exception {
        JSONObject execution = campaign.getJSONObject("execution");
        int total = CampaignStore.totalJobs(campaign);
        int completed = CampaignStore.countStatus(campaign, "completed");
        int failed = CampaignStore.countStatus(campaign, "failed");
        int pending = CampaignStore.countStatus(campaign, "pending");
        int running = CampaignStore.countStatus(campaign, "running");
        status.setText(campaign.getString("campaign_id") + " · "
                + execution.optString("state", "unknown") + "\n"
                + "Jobs: " + completed + " concluídos · " + failed + " falhos · "
                + running + " executando · " + pending + " pendentes · total " + total
                + "\nPlano SHA-256: " + campaign.optString("plan_sha256")
                + "\n" + currentCampaignFile.getAbsolutePath());
        String encoded = campaign.toString(2);
        if (encoded.length() > 30_000) encoded = encoded.substring(0, 30_000) + "\n…";
        preview.setText(encoded);
    }

    private void chooseExport() {
        if (currentCampaignFile == null || !currentCampaignFile.isFile()) {
            status.setText("Nenhum campaign.json disponível.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, currentCampaignFile.getParentFile().getName()
                + ".json");
        startActivityForResult(intent, REQUEST_EXPORT_CAMPAIGN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_CAMPAIGN || resultCode != RESULT_OK
                || data == null || data.getData() == null || currentCampaignFile == null) return;
        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("Destino indisponível");
            output.write(ResultFiles.readUtf8(currentCampaignFile)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
            status.setText("campaign.json exportado.");
        } catch (Throwable error) {
            status.setText("Falha ao exportar: " + error.getMessage());
        }
    }

    @Override
    public void onCampaignStatus(String message) {
        status.setText(message);
    }

    @Override
    public void onCampaignUpdated(File campaignFile, JSONObject campaign) {
        currentCampaignFile = campaignFile;
        try {
            showCampaign(campaign);
        } catch (Exception error) {
            status.setText("Campanha atualizada, mas a prévia falhou: " + error.getMessage());
        }
        updateButtons();
    }

    @Override
    public void onCampaignComplete(File campaignFile, JSONObject campaign) {
        currentCampaignFile = campaignFile;
        setBusy(false);
        try {
            showCampaign(campaign);
            status.append("\nCampanha finalizada. Rankings permanecem separados por grupo comparável.");
        } catch (Exception error) {
            status.setText("Campanha concluída: " + error.getMessage());
        }
        updateButtons();
    }

    @Override
    public void onCampaignFailure(String message, Throwable error) {
        setBusy(false);
        status.setText(message + ": " + (error == null ? "falha desconhecida"
                : error.getMessage()));
        updateButtons();
    }

    private void setBusy(boolean value) {
        busy = value;
        for (View control : editableControls) control.setEnabled(!value);
        updateButtons();
    }

    private void updateButtons() {
        boolean active = coordinator != null && coordinator.isActive();
        if (startButton != null) startButton.setEnabled(!busy && !drivers.isEmpty());
        if (resumeButton != null) resumeButton.setEnabled(!active
                && currentCampaignFile != null && currentCampaignFile.isFile());
        if (pauseButton != null) pauseButton.setEnabled(active);
        if (exportButton != null) exportButton.setEnabled(
                currentCampaignFile != null && currentCampaignFile.isFile());
    }

    private int parse(EditText input, String label, int minimum, int maximum) {
        int value;
        try {
            value = Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " inválido");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " deve ficar entre "
                    + minimum + " e " + maximum);
        }
        return value;
    }

    private EditText numeric(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
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
        view.setTextColor(AmaralColors.TEXT_PRIMARY);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(4));
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
