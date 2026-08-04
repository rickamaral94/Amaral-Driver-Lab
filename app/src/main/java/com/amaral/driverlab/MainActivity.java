package com.amaral.driverlab;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;

/** Phase 13 home: guided first, technical depth progressively disclosed. */
public final class MainActivity extends LocalizedActivity {
    private UxPreferenceStore uxPreferences;
    private LinearLayout root;
    private Button modeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppTheme.apply(this);
        uxPreferences = new UxPreferenceStore(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(AmaralColors.BACKGROUND);
        root = AppTheme.vertical(this);
        root.setPadding(dp(18), dp(18), dp(18), dp(42));
        root.setBackgroundColor(AmaralColors.BACKGROUND);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        addHeader();
        addLatestSummary();
        addRecommendedTest();
        addComparisonSection();
        if (uxPreferences.advancedMode()) addIndividualTests();
        addResultsSection();
        if (uxPreferences.advancedMode()) addAdvancedWorkspace();
        setContentView(scroll);
    }

    private void addHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription(getString(R.string.phase13_brand_logo_description));
        header.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout titles = AppTheme.vertical(this);
        TextView title = AppTheme.heading(this, getString(R.string.phase13_home_title), 23);
        title.setLetterSpacing(0.03f);
        titles.addView(title);
        titles.addView(AppTheme.caption(this, getString(R.string.phase13_home_subtitle)),
                AppTheme.matchWrap(this, 4, 0));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(12), 0, dp(8), 0);
        header.addView(titles, titleParams);

        Button language = AppTheme.iconButton(this,
                LanguageManager.current(this).flag,
                getString(R.string.language_selector_content_description),
                view -> LanguageSelectorDialog.show(this));
        header.addView(language, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(header);

        modeButton = AppTheme.ghostButton(this,
                uxPreferences.advancedMode()
                        ? getString(R.string.phase13_mode_advanced)
                        : getString(R.string.phase13_mode_basic),
                view -> {
                    uxPreferences.setAdvancedMode(!uxPreferences.advancedMode());
                    buildUi();
                });
        modeButton.setContentDescription(getString(R.string.phase13_mode_content_description));
        root.addView(modeButton, AppTheme.matchWrap(this, 16, 18));
    }

    private void addLatestSummary() {
        File latest = latestQualification();
        if (latest == null) return;
        try {
            JSONObject manifest = QualificationStore.load(latest);
            JSONObject execution = manifest.optJSONObject("execution");
            JSONObject report = manifest.optJSONObject("report");
            LinearLayout card = AppTheme.card(this);
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(AppTheme.heading(this, getString(R.string.phase13_latest_result), 16),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            String state = execution == null ? "pending" : execution.optString("state", "pending");
            top.addView(AppTheme.chip(this, localizedState(state), stateColor(state)));
            card.addView(top);
            String headline = getString(R.string.phase13_result_in_progress);
            if (report != null && report.optJSONObject("human_summary") != null) {
                headline = report.optJSONObject("human_summary")
                        .optString("headline", headline);
            }
            card.addView(AppTheme.body(this, headline), AppTheme.matchWrap(this, 12, 0));
            card.setOnClickListener(view -> open(GuidedTestFlowActivity.class,
                    GuidedTestFlowActivity.EXTRA_START_STEP, 4));
            card.setContentDescription(getString(R.string.phase13_open_latest_result));
            root.addView(card, AppTheme.matchWrap(this, 0, 16));
        } catch (Exception ignored) {
            // The dashboard remains available even if a stale report cannot be parsed.
        }
    }

    private void addRecommendedTest() {
        sectionTitle(R.string.phase13_recommended_section);
        LinearLayout hero = AppTheme.card(this);
        hero.setBackground(AppTheme.rounded(AmaralColors.SURFACE_ELEVATED, 24,
                AmaralColors.BRAND_PRIMARY, 1, this));
        hero.addView(AppTheme.chip(this, getString(R.string.phase13_status_recommended),
                AmaralColors.SUCCESS), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = AppTheme.heading(this, getString(R.string.phase13_full_title), 24);
        hero.addView(title, AppTheme.matchWrap(this, 14, 8));
        hero.addView(AppTheme.body(this, getString(R.string.phase13_full_description)));
        TextView composition = AppTheme.caption(this, getString(R.string.phase13_full_composition));
        composition.setTypeface(Typeface.MONOSPACE);
        hero.addView(composition, AppTheme.matchWrap(this, 14, 14));
        hero.addView(AppTheme.primaryButton(this, getString(R.string.phase13_start_full),
                view -> open(GuidedTestFlowActivity.class)),
                AppTheme.matchWrap(this, 0, 0));
        root.addView(hero, AppTheme.matchWrap(this, 0, 20));
    }

    private void addComparisonSection() {
        sectionTitle(R.string.phase13_comparisons);
        LinearLayout card = AppTheme.card(this);
        card.addView(actionButton(R.string.phase13_system_vs_turnip,
                R.string.phase13_system_vs_turnip_detail,
                view -> openGuided("system_vs_turnip")));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_turnip_vs_turnip,
                R.string.phase13_turnip_vs_turnip_detail,
                view -> openGuided("turnip_vs_turnip")));
        root.addView(card, AppTheme.matchWrap(this, 0, 20));
    }

    private void addIndividualTests() {
        sectionTitle(R.string.phase13_individual_tests);
        LinearLayout card = AppTheme.card(this);
        card.addView(actionButton(R.string.phase13_visual_scenes,
                R.string.phase13_visual_scenes_detail,
                view -> openAdvancedAt(1)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_performance,
                R.string.phase13_performance_detail,
                view -> openAdvancedAt(5)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_deep_diagnostics,
                R.string.phase13_deep_diagnostics_detail,
                view -> open(DeepDiagnosticsActivity.class)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_soak_test,
                R.string.phase13_soak_test_detail,
                view -> open(DeepDiagnosticsActivity.class)));
        root.addView(card, AppTheme.matchWrap(this, 0, 20));
    }

    private void addResultsSection() {
        sectionTitle(R.string.phase13_results);
        LinearLayout card = AppTheme.card(this);
        card.addView(actionButton(R.string.phase13_history,
                R.string.phase13_history_detail, view -> open(Phase4Activity.class)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_rankings,
                R.string.phase13_rankings_detail, view -> open(Phase4Activity.class)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_reports,
                R.string.phase13_reports_detail, view -> open(AdvancedSettingsActivity.class)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_telemetry,
                R.string.phase13_telemetry_detail, view -> open(TelemetryActivity.class)));
        root.addView(card, AppTheme.matchWrap(this, 0, 20));
    }

    private void addAdvancedWorkspace() {
        sectionTitle(R.string.phase13_advanced_tools);
        LinearLayout card = AppTheme.card(this);
        card.addView(actionButton(R.string.phase13_technical_workspace,
                R.string.phase13_technical_workspace_detail,
                view -> open(AdvancedSettingsActivity.class)));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_regression_campaigns,
                R.string.phase13_regression_campaigns_detail,
                view -> open(CampaignActivity.class)));
        root.addView(card, AppTheme.matchWrap(this, 0, 8));
    }

    private View actionButton(int titleRes, int detailRes, View.OnClickListener listener) {
        LinearLayout item = AppTheme.vertical(this);
        item.setPadding(dp(4), dp(12), dp(4), dp(12));
        TextView title = AppTheme.heading(this, getString(titleRes), 16);
        TextView detail = AppTheme.body(this, getString(detailRes));
        item.addView(title);
        item.addView(detail, AppTheme.matchWrap(this, 5, 0));
        item.setBackground(AppTheme.rounded(AmaralColors.SURFACE, 12,
                AmaralColors.SURFACE, 0, this));
        item.setOnClickListener(listener);
        item.setFocusable(true);
        item.setClickable(true);
        item.setContentDescription(getString(titleRes) + ". " + getString(detailRes));
        item.setMinimumHeight(dp(64));
        return item;
    }

    private void sectionTitle(int stringRes) {
        root.addView(AppTheme.heading(this, getString(stringRes), 18),
                AppTheme.matchWrap(this, 0, 10));
    }

    private void openGuided(String comparisonMode) {
        Intent intent = new Intent(this, GuidedTestFlowActivity.class);
        intent.putExtra(GuidedTestFlowActivity.EXTRA_COMPARISON_MODE, comparisonMode);
        startActivity(intent);
    }

    private void openAdvancedAt(int workloadPosition) {
        Intent intent = new Intent(this, AdvancedSettingsActivity.class);
        intent.putExtra(AdvancedSettingsActivity.EXTRA_WORKLOAD_POSITION, workloadPosition);
        startActivity(intent);
    }

    private void open(Class<?> activityClass) {
        startActivity(new Intent(this, activityClass));
    }

    private void open(Class<?> activityClass, String extra, int value) {
        Intent intent = new Intent(this, activityClass);
        intent.putExtra(extra, value);
        startActivity(intent);
    }

    private File latestQualification() {
        File root = new File(getFilesDir(), "qualifications");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) return null;
        java.util.Arrays.sort(directories,
                java.util.Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            File manifest = new File(directory, "qualification.json");
            if (manifest.isFile()) return manifest;
        }
        return null;
    }

    private String localizedState(String state) {
        if (state.startsWith("completed")) return getString(R.string.phase13_status_completed);
        if (state.contains("failed")) return getString(R.string.phase13_status_blocked);
        if (state.contains("paused")) return getString(R.string.phase13_status_paused);
        return getString(R.string.phase13_status_running);
    }

    private int stateColor(String state) {
        if (state.startsWith("completed")) return AmaralColors.SUCCESS;
        if (state.contains("failed")) return AmaralColors.ERROR;
        if (state.contains("paused")) return AmaralColors.WARNING;
        return AmaralColors.INFO;
    }

    private LinearLayout.LayoutParams dividerParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
    }

    private int dp(int value) {
        return AppTheme.dp(this, value);
    }
}
