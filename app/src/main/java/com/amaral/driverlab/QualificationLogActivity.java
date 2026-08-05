package com.amaral.driverlab;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Human-first Full Qualification log with one-tap GitHub issue handoff. */
public final class QualificationLogActivity extends LocalizedActivity {
    static final String EXTRA_QUALIFICATION_PATH = "qualification_path";
    private static final int REQUEST_EXPORT_BUNDLE = 1320;
    private static final String ISSUE_OWNER = "rickamaral94";
    private static final String ISSUE_REPOSITORY = "Amaral-Driver-Lab";

    private JSONObject manifest;
    private File manifestFile;
    private File bundleFile;
    private TextView status;
    private TextView rawLog;
    private Button issueButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppTheme.apply(this);
        resolveQualification();
        buildUi();
    }

    private void resolveQualification() {
        String path = getIntent().getStringExtra(EXTRA_QUALIFICATION_PATH);
        File qualificationsRoot = new File(getFilesDir(), "qualifications");
        File requested = path == null ? null : new File(path);
        try {
            if (requested == null || !requested.isFile()
                    || !ResultFiles.isInside(qualificationsRoot, requested)) {
                throw new IllegalArgumentException(getString(R.string.phase13_log_invalid));
            }
            manifest = QualificationStore.load(requested);
            manifestFile = requested;
            JSONObject bundle = manifest.optJSONObject("diagnostic_bundle");
            if (bundle != null) {
                File candidate = new File(requested.getParentFile(),
                        bundle.optString("relative_path", "diagnostic-bundle.zip"));
                if (candidate.isFile() && ResultFiles.isInside(requested.getParentFile(), candidate)) {
                    bundleFile = candidate;
                }
            }
        } catch (Throwable error) {
            manifest = null;
            manifestFile = null;
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(AmaralColors.BACKGROUND);
        LinearLayout root = AppTheme.vertical(this);
        root.setPadding(dp(18), dp(18), dp(18), dp(40));
        root.setBackgroundColor(AmaralColors.BACKGROUND);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = AppTheme.iconButton(this, "←", getString(R.string.action_back),
                view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = AppTheme.vertical(this);
        titles.addView(AppTheme.heading(this, getString(R.string.phase13_log_title), 22));
        titles.addView(AppTheme.caption(this, getString(R.string.phase13_log_subtitle)),
                AppTheme.matchWrap(this, 4, 0));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titleParams);
        root.addView(header, AppTheme.matchWrap(this, 0, 18));

        if (manifest == null) {
            LinearLayout error = AppTheme.card(this);
            error.addView(AppTheme.heading(this, getString(R.string.phase13_log_invalid), 18));
            error.addView(AppTheme.primaryButton(this, getString(R.string.phase13_back_home),
                    view -> backHome()), AppTheme.matchWrap(this, 16, 0));
            root.addView(error);
            setContentView(scroll);
            return;
        }

        root.addView(summaryCard(), AppTheme.matchWrap(this, 0, 14));
        root.addView(diagnosticCard(getString(R.string.phase13_hardware_target_title),
                QualificationOptimizationReport.hardwareDisplay(manifest)),
                AppTheme.matchWrap(this, 0, 14));
        root.addView(diagnosticCard(getString(R.string.phase13_metrics_comparison_title),
                QualificationOptimizationReport.metricsDisplay(manifest)),
                AppTheme.matchWrap(this, 0, 14));

        issueButton = AppTheme.primaryButton(this, getString(R.string.phase13_send_issue),
                view -> sendIssue());
        root.addView(issueButton, AppTheme.matchWrap(this, 0, 8));
        root.addView(AppTheme.secondaryButton(this, getString(R.string.phase13_export_log),
                view -> exportBundle()), AppTheme.matchWrap(this, 0, 8));
        root.addView(AppTheme.secondaryButton(this, getString(R.string.phase13_copy_log),
                view -> copyLog()), AppTheme.matchWrap(this, 0, 8));
        root.addView(AppTheme.ghostButton(this, getString(R.string.phase13_back_home),
                view -> backHome()), AppTheme.matchWrap(this, 0, 16));

        status = AppTheme.caption(this, "");
        status.setTextIsSelectable(true);
        root.addView(status, AppTheme.matchWrap(this, 0, 14));

        LinearLayout rawCard = AppTheme.card(this);
        rawCard.addView(AppTheme.heading(this, getString(R.string.phase13_log_raw_title), 16));
        rawLog = AppTheme.caption(this, pretty(manifest));
        rawLog.setTextIsSelectable(true);
        rawLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        rawCard.addView(rawLog, AppTheme.matchWrap(this, 12, 0));
        root.addView(rawCard);
        setContentView(scroll);
    }

    private LinearLayout summaryCard() {
        LinearLayout card = AppTheme.card(this);
        JSONObject execution = manifest.optJSONObject("execution");
        JSONObject report = manifest.optJSONObject("report");
        JSONObject human = report == null ? null : report.optJSONObject("human_summary");
        JSONObject score = report == null ? null : report.optJSONObject("score");
        String state = execution == null ? "unknown" : execution.optString("state", "unknown");
        int stateColor = state.startsWith("completed") ? AmaralColors.SUCCESS
                : state.contains("failed") ? AmaralColors.ERROR
                : state.contains("paused") ? AmaralColors.WARNING : AmaralColors.INFO;
        card.addView(AppTheme.chip(this, localizedState(state), stateColor),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(AppTheme.heading(this, human == null
                        ? getString(R.string.phase13_result_in_progress)
                        : human.optString("headline", getString(R.string.phase13_log_title)), 20),
                AppTheme.matchWrap(this, 14, 6));
        if (human != null) card.addView(AppTheme.body(this, human.optString("detail", "")));

        JSONObject candidate = manifest.optJSONObject("driver");
        JSONObject reference = manifest.optJSONObject("reference_driver");
        String comparisonMode = manifest.optString("comparison_mode", "system_vs_turnip");
        String referenceLabel = "turnip_vs_turnip".equals(comparisonMode)
                ? driverLabel(reference) : getString(R.string.phase13_system_driver);
        String candidateLabel = driverLabel(candidate);
        TextView comparison = AppTheme.body(this,
                getString(R.string.phase13_comparison_summary_format,
                        referenceLabel, candidateLabel));
        card.addView(comparison, AppTheme.matchWrap(this, 14, 8));

        if (score != null) {
            String metrics = getString(R.string.phase13_log_metrics_format,
                    format(score.optDouble("performance_index", Double.NaN)),
                    format(score.optDouble("compatibility_index", Double.NaN)),
                    formatPercent(score.optDouble("weighted_improvement_percent", Double.NaN)),
                    score.optString("confidence", "—"));
            card.addView(AppTheme.body(this, metrics));
            JSONArray reasons = score.optJSONArray("gate_reasons");
            if (reasons != null && reasons.length() > 0) {
                StringBuilder blockers = new StringBuilder(getString(R.string.phase13_log_blockers));
                for (int index = 0; index < reasons.length(); index++) {
                    blockers.append("\n• ").append(reasons.optString(index));
                }
                card.addView(AppTheme.body(this, blockers), AppTheme.matchWrap(this, 12, 0));
            }
        }
        if (execution != null) {
            card.addView(AppTheme.caption(this,
                    getString(R.string.phase13_log_steps_format,
                            QualificationStore.countStatus(manifest, "completed"),
                            QualificationStore.countStatus(manifest, "failed"),
                            QualificationStore.countStatus(manifest, "pending"))),
                    AppTheme.matchWrap(this, 12, 0));
        }
        return card;
    }

    private LinearLayout diagnosticCard(String title, String content) {
        LinearLayout card = AppTheme.card(this);
        card.addView(AppTheme.heading(this, title, 16));
        TextView body = AppTheme.body(this, content);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        card.addView(body, AppTheme.matchWrap(this, 10, 0));
        return card;
    }

    private void sendIssue() {
        if (manifest == null) return;
        SecureTokenStore tokenStore = new SecureTokenStore(this);
        String token = tokenStore.load();
        if (token == null) {
            try {
                GitHubIssuePublisher.openQualificationDraft(this,
                        ISSUE_OWNER, ISSUE_REPOSITORY, manifest);
                setStatus(getString(R.string.phase13_issue_opened));
            } catch (Throwable error) {
                setStatus(getString(R.string.phase13_issue_failed, error.getMessage()));
            }
            return;
        }
        issueButton.setEnabled(false);
        setStatus(getString(R.string.phase13_issue_sending));
        new Thread(() -> {
            try {
                String url = GitHubIssuePublisher.publishQualification(token,
                        ISSUE_OWNER, ISSUE_REPOSITORY, manifest);
                runOnUiThread(() -> {
                    issueButton.setEnabled(true);
                    setStatus(getString(R.string.phase13_issue_created, url));
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    issueButton.setEnabled(true);
                    setStatus(getString(R.string.phase13_issue_failed, error.getMessage()));
                });
            }
        }, "qualification-github-issue").start();
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Amaral Driver Lab", pretty(manifest)));
        Toast.makeText(this, getString(R.string.phase13_log_copied), Toast.LENGTH_SHORT).show();
    }

    private void exportBundle() {
        if (bundleFile == null || !bundleFile.isFile()) {
            setStatus(getString(R.string.phase13_export_failed,
                    getString(R.string.phase13_bundle_unavailable)));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,
                manifest.optString("qualification_id", "qualification") + "-full-log.zip");
        startActivityForResult(intent, REQUEST_EXPORT_BUNDLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_BUNDLE || resultCode != RESULT_OK
                || data == null || data.getData() == null || bundleFile == null) return;
        try (FileInputStream input = new FileInputStream(bundleFile);
             OutputStream output = getContentResolver().openOutputStream(data.getData(), "w")) {
            if (output == null) throw new IllegalStateException("Destination unavailable");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.flush();
            setStatus(getString(R.string.phase13_export_success));
        } catch (Throwable error) {
            setStatus(getString(R.string.phase13_export_failed, error.getMessage()));
        }
    }

    private void backHome() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private String localizedState(String state) {
        if (state.startsWith("completed")) return getString(R.string.phase13_status_completed);
        if (state.contains("failed")) return getString(R.string.phase13_status_blocked);
        if (state.contains("paused")) return getString(R.string.phase13_status_paused);
        return getString(R.string.phase13_status_running);
    }

    private String driverLabel(JSONObject driver) {
        if (driver == null) return "—";
        String name = driver.optString("name", "Turnip");
        String version = driver.optString("packageVersion",
                driver.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }

    private String pretty(JSONObject value) {
        try { return value.toString(2); }
        catch (Exception ignored) { return value.toString(); }
    }

    private String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.1f", value) : "—";
    }

    private String formatPercent(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%+.2f%%", value) : "—";
    }

    private int dp(int value) { return AppTheme.dp(this, value); }
}
