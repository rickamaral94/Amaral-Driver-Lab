package com.amaral.driverlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Phase4Activity extends Activity {
    private static final int REQUEST_IMPORT_SUITE = 4001;
    private static final int REQUEST_EXPORT_ENVELOPE = 4002;

    private final List<SuiteRecord> allRecords = new ArrayList<>();
    private final List<SuiteRecord> filteredRecords = new ArrayList<>();
    private final List<String> workloadValues = new ArrayList<>();
    private final List<String> hardwareValues = new ArrayList<>();

    private Spinner workloadFilter;
    private Spinner hardwareFilter;
    private Spinner sortSpinner;
    private Spinner leftSpinner;
    private Spinner rightSpinner;
    private CheckBox includeBlocked;
    private TextView status;
    private TextView preview;
    private JSONObject pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshHistory();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Fase 4 · Histórico e regressões", 24, true));
        TextView note = text(Phase4Contract.LIMITATION, 13, false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(0, 4, 0, 14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("ATUALIZAR", view -> refreshHistory());
        Button importSuite = button("IMPORTAR SUITE.JSON", view -> chooseSuite());
        actions.addView(refresh, weighted());
        actions.addView(importSuite, weighted());
        root.addView(actions, margins(0, 0, 0, 10));

        root.addView(text("Filtros", 18, true));
        workloadFilter = new Spinner(this);
        hardwareFilter = new Spinner(this);
        sortSpinner = new Spinner(this);
        sortSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Mais recentes", "Maior melhoria", "Nome do driver"}));
        AdapterView.OnItemSelectedListener filters = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                applyFilters();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        workloadFilter.setOnItemSelectedListener(filters);
        hardwareFilter.setOnItemSelectedListener(filters);
        sortSpinner.setOnItemSelectedListener(filters);
        root.addView(workloadFilter, margins(0, 4, 0, 4));
        root.addView(hardwareFilter, margins(0, 0, 0, 4));
        root.addView(sortSpinner, margins(0, 0, 0, 4));
        includeBlocked = new CheckBox(this);
        includeBlocked.setText("Incluir suítes com validade bloqueada");
        includeBlocked.setOnCheckedChangeListener((buttonView, isChecked) -> applyFilters());
        root.addView(includeBlocked, margins(0, 0, 0, 12));

        root.addView(text("Comparação lado a lado", 18, true));
        leftSpinner = new Spinner(this);
        rightSpinner = new Spinner(this);
        root.addView(leftSpinner, margins(0, 4, 0, 4));
        root.addView(rightSpinner, margins(0, 0, 0, 8));

        Button compare = button("COMPARAR SELECIONADAS", view -> showDiff());
        Button ranking = button("RANKING DO GRUPO", view -> showRanking());
        Button bisect = button("BISECT DA SEQUÊNCIA", view -> showBisect());
        Button anonymous = button("EXPORTAR ENVELOPE ANÔNIMO", view -> confirmAnonymousExport());
        root.addView(compare);
        root.addView(ranking, margins(0, 4, 0, 0));
        root.addView(bisect, margins(0, 4, 0, 0));
        root.addView(anonymous, margins(0, 4, 0, 14));

        status = text("Carregando histórico…", 14, false);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 0, 0, 8));
        preview = text("", 12, false);
        preview.setTextIsSelectable(true);
        preview.setBackgroundColor(0xffeeeeee);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(preview);
        setContentView(scroll);
    }

    private void refreshHistory() {
        allRecords.clear();
        allRecords.addAll(SuiteHistory.scan(getFilesDir()));
        rebuildFilterAdapters();
        applyFilters();
    }

    private void rebuildFilterAdapters() {
        String selectedWorkload = selectedValue(workloadValues, workloadFilter);
        String selectedHardware = selectedValue(hardwareValues, hardwareFilter);

        workloadValues.clear();
        workloadValues.add("");
        workloadValues.addAll(SuiteHistory.workloads(allRecords));
        List<String> workloadLabels = new ArrayList<>();
        workloadLabels.add("Todos os workloads");
        for (int index = 1; index < workloadValues.size(); ++index) {
            String id = workloadValues.get(index);
            workloadLabels.add(WorkloadContract.labelFor(id));
        }
        workloadFilter.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, workloadLabels));
        workloadFilter.setSelection(indexOf(workloadValues, selectedWorkload));

        hardwareValues.clear();
        hardwareValues.add("");
        hardwareValues.addAll(SuiteHistory.hardwareKeys(allRecords));
        List<String> hardwareLabels = new ArrayList<>();
        hardwareLabels.add("Todos os aparelhos / SoCs / GPUs");
        for (int index = 1; index < hardwareValues.size(); ++index) {
            hardwareLabels.add(hardwareValues.get(index));
        }
        hardwareFilter.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, hardwareLabels));
        hardwareFilter.setSelection(indexOf(hardwareValues, selectedHardware));
    }

    private void applyFilters() {
        if (workloadFilter == null || hardwareFilter == null || sortSpinner == null) return;
        String workload = valueAt(workloadValues, workloadFilter.getSelectedItemPosition());
        String hardware = valueAt(hardwareValues, hardwareFilter.getSelectedItemPosition());
        SuiteHistory.SortOrder sort = sortSpinner.getSelectedItemPosition() == 1
                ? SuiteHistory.SortOrder.IMPROVEMENT
                : sortSpinner.getSelectedItemPosition() == 2
                ? SuiteHistory.SortOrder.DRIVER : SuiteHistory.SortOrder.NEWEST;
        filteredRecords.clear();
        filteredRecords.addAll(SuiteHistory.filter(allRecords, workload, hardware,
                sort, includeBlocked != null && includeBlocked.isChecked()));
        List<String> labels = new ArrayList<>();
        for (SuiteRecord record : filteredRecords) labels.add(record.displayLabel());
        if (labels.isEmpty()) labels.add("Nenhuma suíte compatível com os filtros");
        leftSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        rightSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (filteredRecords.size() > 1) rightSpinner.setSelection(1);
        status.setText(allRecords.size() + " suíte(s) indexada(s) · "
                + filteredRecords.size() + " visível(is)");
        preview.setText(historyPreview());
    }

    private void showDiff() {
        try {
            SuiteRecord left = selectedRecord(leftSpinner);
            SuiteRecord right = selectedRecord(rightSpinner);
            showJson(SuiteDiff.compare(left, right));
        } catch (Throwable error) {
            status.setText("Diff indisponível: " + error.getMessage());
        }
    }

    private void showRanking() {
        try {
            SuiteRecord reference = selectedRecord(leftSpinner);
            showJson(SuiteHistory.ranking(allRecords, reference));
        } catch (Throwable error) {
            status.setText("Ranking indisponível: " + error.getMessage());
        }
    }

    private void showBisect() {
        try {
            if (filteredRecords.isEmpty()) throw new IllegalStateException("Sem sequência filtrada");
            showJson(RegressionBisect.analyze(filteredRecords));
        } catch (Throwable error) {
            status.setText("Bisect indisponível: " + error.getMessage());
        }
    }

    private void confirmAnonymousExport() {
        try {
            SuiteRecord record = selectedRecord(rightSpinner);
            JSONObject envelope = PublicDatasetEnvelope.create(record);
            if (!PublicDatasetEnvelope.verify(envelope)) {
                throw new IllegalStateException("Falha na assinatura de integridade");
            }
            new AlertDialog.Builder(this)
                    .setTitle("Exportar conjunto público")
                    .setMessage("O envelope remove modelo do aparelho, fingerprint e caminhos locais. "
                            + "Ele mantém SoC, GPU, workload, configuração e hash do ZIP, que podem "
                            + "ser correlacionáveis. Nenhum envio ocorre automaticamente.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Exportar", (dialog, which) -> exportEnvelope(envelope))
                    .show();
        } catch (Throwable error) {
            status.setText("Envelope recusado: " + error.getMessage());
        }
    }

    private void exportEnvelope(JSONObject envelope) {
        pendingExport = envelope;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/json");
        create.putExtra(Intent.EXTRA_TITLE, "amaral-driver-lab-public-dataset.json");
        startActivityForResult(create, REQUEST_EXPORT_ENVELOPE);
    }

    private void chooseSuite() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/json");
        startActivityForResult(picker, REQUEST_IMPORT_SUITE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_SUITE) importSuite(uri);
        else if (requestCode == REQUEST_EXPORT_ENVELOPE) writeEnvelope(uri);
    }

    private void importSuite(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Arquivo indisponível");
            String encoded = readLimited(input, Phase4Contract.MAX_IMPORTED_SUITE_BYTES);
            JSONObject report = new JSONObject(encoded);
            SuiteRecord.parse(null, report);
            File directory = new File(getFilesDir(), "imported-suites");
            String digest = JsonCanonicalizer.sha256(report);
            File target = new File(directory, "suite-" + digest.substring(0, 16) + ".json");
            ResultFiles.writeAtomic(target, report.toString(2));
            status.setText("Suite importada: " + target.getName());
            refreshHistory();
        } catch (Throwable error) {
            status.setText("Importação recusada: " + error.getMessage());
        }
    }

    private void writeEnvelope(Uri uri) {
        if (pendingExport == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("Destino indisponível");
            output.write(pendingExport.toString(2).getBytes(StandardCharsets.UTF_8));
            output.flush();
            status.setText("Envelope público exportado e assinatura verificada.");
        } catch (Throwable error) {
            status.setText("Exportação falhou: " + error.getMessage());
        } finally {
            pendingExport = null;
        }
    }

    private SuiteRecord selectedRecord(Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= filteredRecords.size()) {
            throw new IllegalStateException("Selecione uma suíte válida");
        }
        return filteredRecords.get(position);
    }

    private String historyPreview() {
        StringBuilder output = new StringBuilder();
        int limit = Math.min(filteredRecords.size(), 100);
        for (int index = 0; index < limit; ++index) {
            SuiteRecord item = filteredRecords.get(index);
            output.append(index + 1).append(". ").append(item.displayLabel());
            if (item.blockingValidity) output.append(" · BLOQUEADA");
            output.append('\n');
        }
        if (filteredRecords.size() > limit) {
            output.append("… +").append(filteredRecords.size() - limit).append(" suíte(s)");
        }
        return output.toString();
    }

    private void showJson(JSONObject value) throws Exception {
        preview.setText(value.toString(2));
        status.setText("Análise concluída.");
    }

    private static String readLimited(InputStream input, long maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > maximum) {
                throw new IllegalArgumentException("suite.json excede 2 MiB");
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String selectedValue(List<String> values, Spinner spinner) {
        if (spinner == null) return "";
        return valueAt(values, spinner.getSelectedItemPosition());
    }

    private static String valueAt(List<String> values, int position) {
        return position >= 0 && position < values.size() ? values.get(position) : "";
    }

    private static int indexOf(List<String> values, String selected) {
        int index = values.indexOf(selected);
        return index < 0 ? 0 : index;
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
