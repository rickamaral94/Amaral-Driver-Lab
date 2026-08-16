package com.amaral.driverlab;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Ranking v5 UI. Every displayed value comes from the pure DriverRanking result. */
public final class DriverRankingActivity extends LocalizedActivity {
    private static final int REQUEST_EXPORT = 8101;
    private static final int REQUEST_IMPORT = 8102;
    private static final int MAX_IMPORT_BYTES = 20 * 1024 * 1024;

    private LinearLayout content;
    private TextView status;
    private JSONObject fingerprint;
    private List<MeasurementRecord> records = new ArrayList<>();
    private RankingResult result;
    private boolean sortByLow;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppTheme.apply(this);
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) reload();
    }

    private void reload() {
        try {
            fingerprint = EnvironmentFingerprint.capture(this);
            records = RankingStore.load(getFilesDir());
            JSONObject primary = primaryAnchor();
            result = DriverRanking.computeRanking(records, primary, stockAnchor(),
                    fingerprint.getString("epoch_sha256"));
            buildUi();
        } catch (Throwable error) {
            buildError(error);
        }
    }

    private void buildUi() throws Exception {
        ScrollView scroll = new ScrollView(this);
        content = AppTheme.vertical(this);
        content.setPadding(dp(18), dp(18), dp(18), dp(42));
        content.setBackgroundColor(AmaralColors.BACKGROUND);
        scroll.addView(content);
        content.addView(AppTheme.heading(this, "Ranking de drivers Turnip", 25));
        content.addView(AppTheme.body(this,
                "Índice composto do aparelho atual. Compatibilidade é porta: quem quebra não recebe nota."),
                AppTheme.matchWrap(this, 6, 14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(AppTheme.primaryButton(this, "EXECUTAR TESTE NULO",
                view -> startNullTest()));
        actions.addView(AppTheme.secondaryButton(this, "EXPORTAR HISTÓRICO ASSINADO",
                view -> chooseExport()), AppTheme.matchWrap(this, 6, 0));
        actions.addView(AppTheme.secondaryButton(this, "IMPORTAR HISTÓRICO",
                view -> chooseImport()), AppTheme.matchWrap(this, 6, 0));
        content.addView(actions, AppTheme.matchWrap(this, 0, 16));

        status = AppTheme.caption(this, records.size() + " registro(s) brutos · piso de ruído "
                + String.format(Locale.US, "%.2f", result.noiseFloor * 100.0)
                + " pontos por 100");
        content.addView(status, AppTheme.matchWrap(this, 0, 14));

        if (result.blocked) {
            LinearLayout warning = AppTheme.card(this);
            warning.setBackground(AppTheme.rounded(AmaralColors.SURFACE_ELEVATED, 18,
                    AmaralColors.WARNING, 1, this));
            warning.addView(AppTheme.heading(this, "Ranking bloqueado", 20));
            warning.addView(AppTheme.body(this, blockedReason(result.blockedReason)),
                    AppTheme.matchWrap(this, 8, 0));
            content.addView(warning, AppTheme.matchWrap(this, 0, 18));
        } else {
            addRankingTable();
            addReferenceRows();
        }
        addCollapsible("Desclassificados", result.disqualified);
        addCollapsible("Inelegíveis", result.ineligible);
        if (result.anchorDrift != null) {
            content.addView(AppTheme.caption(this, "Deriva do aparelho: "
                    + result.anchorDrift.optString("status", "ausente")),
                    AppTheme.matchWrap(this, 8, 0));
        }
        addFooter();
        setContentView(scroll);
    }

    private void addRankingTable() {
        LinearLayout card = AppTheme.card(this);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(AppTheme.heading(this, "#  Driver · Vulkan · Score", 17),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button toggle = AppTheme.ghostButton(this, sortByLow ? "ORDEM: L" : "ORDEM: SCORE",
                view -> { sortByLow = !sortByLow; try { buildUi(); } catch (Exception ignored) {} });
        header.addView(toggle);
        card.addView(header);

        List<DriverEntry> displayed = new ArrayList<>(result.ranked);
        if (sortByLow) displayed.sort(Comparator
                .comparingDouble((DriverEntry entry) -> entry.low).reversed());
        if (displayed.isEmpty()) {
            card.addView(AppTheme.body(this,
                    "Ainda não há driver com três sessões AB/BA íntegras nesta época."),
                    AppTheme.matchWrap(this, 12, 0));
        }
        for (DriverEntry entry : displayed) {
            TextView row = AppTheme.body(this, entry.rank + "  " + entry.displayName
                    + " · " + absent(entry.apiVersion) + " · " + entry.score);
            row.setPadding(dp(6), dp(13), dp(6), dp(13));
            row.setOnClickListener(view -> showDetails(entry));
            card.addView(AppTheme.divider(this));
            card.addView(row);
        }
        content.addView(card, AppTheme.matchWrap(this, 0, 16));
    }

    private void addReferenceRows() {
        if (result.references.isEmpty()) return;
        LinearLayout card = AppTheme.card(this);
        card.addView(AppTheme.heading(this, "Referências fora da disputa", 17));
        for (DriverEntry entry : result.references) {
            String value = entry.score != null ? "Score " + entry.score : "";
            if (entry.vsStock != null) value += (value.isEmpty() ? "" : " · ")
                    + "vs_stock " + entry.vsStock;
            if (value.isEmpty()) value = "—";
            card.addView(AppTheme.body(this, entry.displayName + " · " + value),
                    AppTheme.matchWrap(this, 9, 0));
        }
        content.addView(card, AppTheme.matchWrap(this, 0, 16));
    }

    private void addCollapsible(String title, List<DriverEntry> entries) {
        LinearLayout card = AppTheme.card(this);
        LinearLayout rows = AppTheme.vertical(this);
        rows.setVisibility(View.GONE);
        Button button = AppTheme.ghostButton(this, title + " (" + entries.size() + ")",
                view -> rows.setVisibility(rows.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        card.addView(button);
        for (DriverEntry entry : entries) {
            StringBuilder reason = new StringBuilder();
            for (int index = 0; index < entry.reasons.length(); index++) {
                JSONObject item = entry.reasons.optJSONObject(index);
                if (index > 0) reason.append(", ");
                reason.append(item == null ? entry.reasons.optString(index)
                        : item.optString("code"));
            }
            TextView row = AppTheme.body(this, entry.displayName + "\n" + reason);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            row.setOnClickListener(view -> showDetails(entry));
            rows.addView(row);
        }
        card.addView(rows);
        content.addView(card, AppTheme.matchWrap(this, 0, 10));
    }

    private void addFooter() {
        JSONObject epoch = fingerprint.optJSONObject("epoch");
        String footer = "Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nGPU/KGSL: " + (epoch == null ? "ausente" : epoch.optString("kgsl", "ausente"))
                + "\nÂncora: v3 " + DriverRanking.PRIMARY_ANCHOR_SHA256.substring(0, 12)
                + " · Mesa " + DriverRanking.PRIMARY_ANCHOR_MESA_COMMIT
                + "\nÉpoca: " + fingerprint.optString("epoch_sha256").substring(0, 12)
                + "\nÚltimo teste nulo: " + latestNullLabel();
        content.addView(AppTheme.caption(this, footer), AppTheme.matchWrap(this, 14, 0));
    }

    private void showDetails(DriverEntry entry) {
        StringBuilder message = new StringBuilder()
                .append("Estado: ").append(entry.state.name().toLowerCase())
                .append("\nScore: ").append(entry.score == null ? "ausente" : entry.score)
                .append("\nS: ").append(finite(entry.sustained))
                .append("\nL: ").append(finite(entry.low))
                .append("\nIC95: ").append(Double.isFinite(entry.ciLow)
                        ? String.format(Locale.US, "%.1f–%.1f", entry.ciLow, entry.ciHigh)
                        : "ausente")
                .append("\nn: ").append(entry.n)
                .append("\nvs_stock: ").append(entry.vsStock == null ? "ausente" : entry.vsStock)
                .append("\nConfiança da âncora: ").append(entry.anchorConfidence)
                .append("\nHistórico preservado: ").append(entry.history.length()).append(" registro(s)");
        new LocalizedAlertDialogBuilder(this).setTitle(entry.displayName)
                .setMessage(message.toString()).setPositiveButton("Fechar", null).show();
    }

    private JSONObject primaryAnchor() throws Exception {
        return new JSONObject()
                .put("binary_sha256", DriverRanking.PRIMARY_ANCHOR_SHA256)
                .put("mesa_commit", DriverRanking.PRIMARY_ANCHOR_MESA_COMMIT)
                .put("driver_name", "Âncora Turnip v3 auditada")
                .put("device_fingerprint_sha256",
                        fingerprint.getString("device_fingerprint_sha256"))
                .put("workload_set_sha256", fingerprint.getString("workload_set_sha256"));
    }

    private JSONObject stockAnchor() throws Exception {
        for (MeasurementRecord record : records) {
            JSONObject identity = record.referenceIdentity();
            if (identity != null && "qualcomm_proprietary".equals(
                    identity.optString("runtime_vendor"))
                    && fingerprint.getString("device_fingerprint_sha256").equals(
                    record.deviceFingerprint())) return identity;
        }
        return null;
    }

    private void startNullTest() {
        Intent intent = new Intent(this, QualificationActivity.class);
        intent.putExtra(QualificationActivity.EXTRA_COMPARISON_MODE, "null_test");
        startActivity(intent);
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "adl-ranking-history.json");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_EXPORT) {
                JSONObject envelope = RankingStore.exportEnvelope(getFilesDir(), records, fingerprint);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IllegalStateException("Destino indisponível");
                    output.write(envelope.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } else if (requestCode == REQUEST_IMPORT) {
                JSONObject envelope = new JSONObject(readLimited(uri));
                RankingStore.importEnvelope(getFilesDir(), envelope,
                        fingerprint.getString("device_fingerprint_sha256"));
            }
            reload();
        } catch (Throwable error) {
            if (status != null) status.setText("Falha: " + error.getMessage());
        }
    }

    private String readLimited(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Arquivo indisponível");
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_IMPORT_BYTES) throw new IllegalArgumentException("Arquivo excede 20 MiB");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String latestNullLabel() {
        long latest = 0L;
        for (MeasurementRecord record : records) if (record.nullTest()) {
            latest = Math.max(latest, record.timestampMs());
        }
        return latest == 0L ? "ausente" : new java.util.Date(latest).toString();
    }

    private String blockedReason(String code) {
        if ("null_test_missing".equals(code)) {
            return "Execute um teste nulo (o mesmo driver nos dois braços) antes da campanha.";
        }
        return "O IC95 do último teste nulo não contém 100. Há viés sistemático na bancada; "
                + "nenhum ranking será renderizado até uma nova época válida.";
    }

    private String absent(String value) { return value == null || value.isEmpty() ? "ausente" : value; }
    private String finite(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.4f", value) : "ausente";
    }
    private int dp(int value) { return AppTheme.dp(this, value); }

    private void buildError(Throwable error) {
        TextView view = new TextView(this);
        view.setPadding(24, 24, 24, 24);
        view.setText("Falha ao abrir o ranking: " + error.getMessage());
        setContentView(view);
    }
}
