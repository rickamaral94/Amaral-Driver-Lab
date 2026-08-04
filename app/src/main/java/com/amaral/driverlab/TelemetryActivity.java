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

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TelemetryActivity extends LocalizedActivity {
    private static final int REQUEST_IMPORT_SESSION = 9001;
    private static final int REQUEST_EXPORT_SESSION = 9002;

    private final List<TelemetrySessionRecord> sessions = new ArrayList<>();
    private final List<SuiteRecord> suites = new ArrayList<>();
    private Spinner sessionSpinner;
    private Spinner leftSpinner;
    private Spinner rightSpinner;
    private Spinner suiteSpinner;
    private TextView status;
    private TextView preview;
    private JSONObject pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Fase 9 · Telemetria de emuladores", 24, true));
        TextView note = text(Phase9Contract.LIMITATION, 13, false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, margins(0, 4, 0, 14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("IMPORTAR SESSION.JSON", view -> chooseSession()), weighted());
        actions.addView(button("ATUALIZAR", view -> refresh()), weighted());
        root.addView(actions, margins(0, 0, 0, 10));

        Button contract = button("VER CONTRATO DO SDK v1", view -> showContract());
        root.addView(contract, margins(0, 0, 0, 16));

        root.addView(text("Sessão selecionada", 18, true));
        sessionSpinner = new Spinner(this);
        sessionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                showSelected();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(sessionSpinner, margins(0, 4, 0, 8));
        root.addView(button("EXPORTAR SESSÃO + RESUMO + LINK",
                view -> exportSelected()), margins(0, 0, 0, 16));

        root.addView(text("Vínculo imutável com uma suíte local", 18, true));
        suiteSpinner = new Spinner(this);
        root.addView(suiteSpinner, margins(0, 4, 0, 8));
        root.addView(button("VINCULAR À SUÍTE SELECIONADA",
                view -> linkSelected()), margins(0, 0, 0, 16));

        root.addView(text("Comparação A/B descritiva", 18, true));
        leftSpinner = new Spinner(this);
        rightSpinner = new Spinner(this);
        root.addView(leftSpinner, margins(0, 4, 0, 4));
        root.addView(rightSpinner, margins(0, 0, 0, 8));
        root.addView(button("COMPARAR SISTEMA × CANDIDATO",
                view -> compareSelected()), margins(0, 0, 0, 14));

        status = text("Carregando…", 14, false);
        status.setTextIsSelectable(true);
        root.addView(status, margins(0, 0, 0, 8));
        preview = text("", 12, false);
        preview.setTextIsSelectable(true);
        preview.setBackgroundColor(0xffeeeeee);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(preview);
        setContentView(scroll);
    }

    private void refresh() {
        sessions.clear();
        sessions.addAll(TelemetryStore.scan(getFilesDir()));
        suites.clear();
        suites.addAll(SuiteHistory.scan(getFilesDir()));

        List<String> sessionLabels = new ArrayList<>();
        for (TelemetrySessionRecord record : sessions) sessionLabels.add(record.displayLabel());
        if (sessionLabels.isEmpty()) sessionLabels.add("Nenhuma sessão importada");
        ArrayAdapter<String> sessionAdapter = new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, sessionLabels);
        sessionSpinner.setAdapter(sessionAdapter);
        leftSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, sessionLabels));
        rightSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, sessionLabels));
        if (sessions.size() > 1) rightSpinner.setSelection(1);

        List<String> suiteLabels = new ArrayList<>();
        for (SuiteRecord suite : suites) suiteLabels.add(suite.displayLabel());
        if (suiteLabels.isEmpty()) suiteLabels.add("Nenhuma suíte local disponível");
        suiteSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, suiteLabels));
        status.setText(sessions.size() + " sessão(ões) · " + suites.size()
                + " suíte(s) disponíveis · nenhum envio automático");
        showSelected();
    }

    private void chooseSession() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/json");
        startActivityForResult(picker, REQUEST_IMPORT_SESSION);
    }

    private void importSession(Uri uri) {
        status.setText("Validando privacidade, versões, limites e SHA-256…");
        new Thread(() -> {
            try {
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IllegalArgumentException("Arquivo indisponível");
                TelemetrySessionRecord imported = TelemetryStore.importSession(
                        getFilesDir(), input);
                runOnUiThread(() -> {
                    refresh();
                    selectSession(imported.sessionId);
                    status.setText("Sessão importada: " + imported.sessionId);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> status.setText(
                        "Importação recusada: " + error.getMessage()));
            }
        }, "telemetry-import").start();
    }

    private void showContract() {
        try {
            preview.setText(Phase9Contract.contractJson().toString(2));
            status.setText("Contrato público do SDK e da importação local.");
        } catch (Exception error) {
            status.setText("Contrato indisponível: " + error.getMessage());
        }
    }

    private void showSelected() {
        try {
            TelemetrySessionRecord record = selectedSession(sessionSpinner);
            JSONObject link = TelemetryStore.readLink(record);
            JSONObject view = new JSONObject()
                    .put("session", record.compactJson())
                    .put("suite_link", link == null ? JSONObject.NULL : link)
                    .put("limitations", Phase9Contract.LIMITATION);
            preview.setText(view.toString(2));
        } catch (Throwable ignored) {
            if (sessions.isEmpty()) preview.setText("Importe um session.json produzido pelo SDK v1.");
        }
    }

    private void compareSelected() {
        try {
            TelemetrySessionRecord left = selectedSession(leftSpinner);
            TelemetrySessionRecord right = selectedSession(rightSpinner);
            JSONObject comparison = TelemetryComparison.compare(left, right);
            preview.setText(comparison.toString(2));
            status.setText(comparison.optBoolean("historically_comparable", false)
                    ? "Comparação descritiva historicamente compatível."
                    : "Comparação bloqueada ou apenas informativa; consulte os checks.");
        } catch (Throwable error) {
            status.setText("Comparação indisponível: " + error.getMessage());
        }
    }

    private void linkSelected() {
        try {
            TelemetrySessionRecord session = selectedSession(sessionSpinner);
            SuiteRecord suite = selectedSuite();
            JSONObject link = TelemetryStore.linkToSuite(getFilesDir(), session, suite);
            preview.setText(link.toString(2));
            status.setText("Vínculo salvo em sidecar; session.json e suite.json não foram alterados.");
        } catch (Throwable error) {
            status.setText("Vínculo recusado: " + error.getMessage());
        }
    }

    private void exportSelected() {
        try {
            pendingExport = TelemetryStore.exportEnvelope(selectedSession(sessionSpinner));
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType("application/json");
            create.putExtra(Intent.EXTRA_TITLE, "amaral-telemetry-export.json");
            startActivityForResult(create, REQUEST_EXPORT_SESSION);
        } catch (Throwable error) {
            status.setText("Exportação indisponível: " + error.getMessage());
        }
    }

    private void writeExport(Uri uri) {
        if (pendingExport == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalArgumentException("Destino indisponível");
            output.write(pendingExport.toString(2).getBytes(StandardCharsets.UTF_8));
            output.flush();
            status.setText("Exportação concluída. Nenhum upload foi realizado.");
        } catch (Throwable error) {
            status.setText("Falha na exportação: " + error.getMessage());
        } finally {
            pendingExport = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_IMPORT_SESSION) importSession(data.getData());
        else if (requestCode == REQUEST_EXPORT_SESSION) writeExport(data.getData());
    }

    private TelemetrySessionRecord selectedSession(Spinner spinner) {
        if (sessions.isEmpty()) throw new IllegalStateException("Nenhuma sessão importada");
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= sessions.size()) position = 0;
        return sessions.get(position);
    }

    private SuiteRecord selectedSuite() {
        if (suites.isEmpty()) throw new IllegalStateException("Nenhuma suíte local");
        int position = suiteSpinner.getSelectedItemPosition();
        if (position < 0 || position >= suites.size()) position = 0;
        return suites.get(position);
    }

    private void selectSession(String sessionId) {
        for (int index = 0; index < sessions.size(); ++index) {
            if (sessions.get(index).sessionId.equals(sessionId)) {
                sessionSpinner.setSelection(index);
                return;
            }
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        if (bold) text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        return text;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
