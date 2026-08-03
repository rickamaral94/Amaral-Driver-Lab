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

    private final List<DriverPackage> drivers = new ArrayList<>();
    private final List<View> controls = new ArrayList<>();

    private SharedPreferences preferences;
    private SecureTokenStore tokenStore;
    private Spinner driverSpinner;
    private Spinner modeSpinner;
    private EditText warmupInput;
    private EditText durationInput;
    private EditText roundsInput;
    private EditText ownerInput;
    private EditText repositoryInput;
    private CheckBox autoIssueCheck;
    private TextView driverDetails;
    private TextView status;
    private TextView resultPreview;
    private Button githubButton;
    private JSONObject lastReport;
    private File lastReportFile;

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

        TextView title = text("Amaral Driver Lab", 26, true);
        root.addView(title);
        TextView subtitle = text(
                "Turnip/stock · processo limpo · telemetria · relatório reproduzível", 14, false);
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

        root.addView(text("2. Protocolo", 19, true), margins(0, 0, 0, 8));
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
        roundsInput = numeric("Rodadas", "3");
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
        root.addView(runButton, margins(0, 4, 0, 20));
        controls.add(runButton);

        root.addView(text("3. GitHub Issues", 19, true), margins(0, 0, 0, 8));
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
            int warmup = parseNumber(warmupInput, "Warm-up", 0, 30);
            int duration = parseNumber(durationInput, "Medição", 1, 120);
            int rounds = parseNumber(roundsInput, "Rodadas", 1, 10);
            saveGitHubTarget();
            setBusy(true);
            resultPreview.setText("");
            new RunCoordinator(this, selectedDriver(), mode, rounds, warmup, duration, this).start();
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
        JSONObject summary = report.optJSONObject("summary");
        double delta = summary == null ? Double.NaN
                : summary.optDouble("candidate_vs_system_percent", Double.NaN);
        String headline = "Suíte concluída";
        if (Double.isFinite(delta)) {
            headline += String.format(Locale.US, " · candidato %+.2f%%", delta);
        }
        status.setText(headline + "\n" + lastReportFile.getAbsolutePath());
        String preview = report.toString(2);
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
        for (View control : controls) control.setEnabled(!busy);
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
