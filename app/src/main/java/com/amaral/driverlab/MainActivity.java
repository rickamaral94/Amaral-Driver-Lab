package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Phase 13 home: choose drivers and run the recommended test without technical detours. */
public final class MainActivity extends LocalizedActivity {
    private static final int REQUEST_IMPORT_DRIVER = 1310;

    private final List<DriverPackage> drivers = new ArrayList<>();
    private UxPreferenceStore uxPreferences;
    private LinearLayout root;
    private Button modeButton;
    private Spinner comparisonSpinner;
    private Spinner candidateSpinner;
    private Spinner referenceSpinner;
    private LinearLayout referenceBlock;
    private TextView candidateDetails;
    private TextView referenceDetails;
    private Button startButton;

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
        addRecommendedTest();
        addLatestSummary();
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
                headline = report.optJSONObject("human_summary").optString("headline", headline);
            }
            card.addView(AppTheme.body(this, headline), AppTheme.matchWrap(this, 12, 0));
            card.setOnClickListener(view -> openLog(latest));
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
        hero.addView(AppTheme.heading(this, getString(R.string.phase13_full_title), 24),
                AppTheme.matchWrap(this, 14, 8));
        hero.addView(AppTheme.body(this, getString(R.string.phase13_quick_test_description)));

        TextView composition = AppTheme.caption(this, getString(R.string.phase13_full_composition));
        composition.setTypeface(Typeface.MONOSPACE);
        hero.addView(composition, AppTheme.matchWrap(this, 12, 16));

        hero.addView(AppTheme.heading(this, getString(R.string.phase13_compare_against), 15));
        comparisonSpinner = new Spinner(this);
        comparisonSpinner.setMinimumHeight(dp(52));
        comparisonSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        getString(R.string.phase13_comparison_system_turnip),
                        getString(R.string.phase13_comparison_turnip_turnip)
                }));
        comparisonSpinner.setSelection("turnip_vs_turnip".equals(
                uxPreferences.quickComparisonMode()) ? 1 : 0);
        hero.addView(comparisonSpinner, AppTheme.matchWrap(this, 6, 12));

        hero.addView(AppTheme.heading(this, getString(R.string.phase13_candidate_driver_label), 15));
        candidateSpinner = new Spinner(this);
        candidateSpinner.setMinimumHeight(dp(52));
        hero.addView(candidateSpinner, AppTheme.matchWrap(this, 6, 4));
        candidateDetails = AppTheme.caption(this, "");
        candidateDetails.setTextIsSelectable(true);
        hero.addView(candidateDetails, AppTheme.matchWrap(this, 0, 12));

        referenceBlock = AppTheme.vertical(this);
        referenceBlock.addView(AppTheme.heading(this,
                getString(R.string.phase13_reference_driver_label), 15));
        referenceSpinner = new Spinner(this);
        referenceSpinner.setMinimumHeight(dp(52));
        referenceBlock.addView(referenceSpinner, AppTheme.matchWrap(this, 6, 4));
        referenceDetails = AppTheme.caption(this, "");
        referenceDetails.setTextIsSelectable(true);
        referenceBlock.addView(referenceDetails, AppTheme.matchWrap(this, 0, 12));
        hero.addView(referenceBlock);

        Button importButton = AppTheme.secondaryButton(this,
                getString(R.string.phase13_import_driver), view -> chooseDriverZip());
        hero.addView(importButton, AppTheme.matchWrap(this, 0, 8));

        startButton = AppTheme.primaryButton(this,
                getString(R.string.phase13_start_selected_test),
                view -> startQuickQualification());
        hero.addView(startButton);
        root.addView(hero, AppTheme.matchWrap(this, 0, 20));

        loadDriverSelectors();
        comparisonSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                uxPreferences.setQuickComparisonMode(position == 1
                        ? "turnip_vs_turnip" : "system_vs_turnip");
                updateReferenceVisibility();
                updateStartState();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateReferenceVisibility();
        updateStartState();
    }

    private void loadDriverSelectors() {
        drivers.clear();
        drivers.addAll(DriverCatalog.load(this));
        List<String> labels = new ArrayList<>();
        if (drivers.isEmpty()) labels.add(getString(R.string.phase13_no_driver_imported));
        for (DriverPackage driver : drivers) labels.add(driver.displayName());

        candidateSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        referenceSpinner.setAdapter(new LocalizedArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        candidateSpinner.setSelection(indexForSha(uxPreferences.quickCandidateSha(), 0));
        int referenceFallback = drivers.size() > 1 ? 1 : 0;
        referenceSpinner.setSelection(indexForSha(
                uxPreferences.quickReferenceSha(), referenceFallback));

        candidateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                DriverPackage selected = driverAt(position);
                if (selected != null) uxPreferences.setQuickCandidateSha(selected.sha256);
                updateDriverDetails(candidateDetails, selected);
                updateStartState();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        referenceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                DriverPackage selected = driverAt(position);
                if (selected != null) uxPreferences.setQuickReferenceSha(selected.sha256);
                updateDriverDetails(referenceDetails, selected);
                updateStartState();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateDriverDetails(candidateDetails, driverAt(candidateSpinner.getSelectedItemPosition()));
        updateDriverDetails(referenceDetails, driverAt(referenceSpinner.getSelectedItemPosition()));
    }

    private void updateReferenceVisibility() {
        boolean turnipVsTurnip = comparisonSpinner != null
                && comparisonSpinner.getSelectedItemPosition() == 1;
        if (referenceBlock != null) referenceBlock.setVisibility(
                turnipVsTurnip ? View.VISIBLE : View.GONE);
    }

    private void updateStartState() {
        if (startButton == null) return;
        DriverPackage candidate = selectedCandidate();
        boolean enabled = candidate != null;
        if (enabled && isTurnipVsTurnip()) {
            DriverPackage reference = selectedReference();
            enabled = reference != null && !candidate.sha256.equals(reference.sha256);
        }
        startButton.setEnabled(enabled);
    }

    private void startQuickQualification() {
        DriverPackage candidate = selectedCandidate();
        if (candidate == null) {
            showMessage(R.string.phase13_select_candidate_error);
            return;
        }
        DriverPackage reference = isTurnipVsTurnip() ? selectedReference() : null;
        if (isTurnipVsTurnip() && reference == null) {
            showMessage(R.string.phase13_select_reference_error);
            return;
        }
        if (reference != null && reference.sha256.equals(candidate.sha256)) {
            showMessage(R.string.phase13_same_driver_error);
            return;
        }
        Intent intent = new Intent(this, QualificationActivity.class);
        intent.putExtra(QualificationActivity.EXTRA_GUIDED, true);
        intent.putExtra(QualificationActivity.EXTRA_AUTOSTART, true);
        intent.putExtra(QualificationActivity.EXTRA_OPEN_LOG_ON_COMPLETE, true);
        intent.putExtra(QualificationActivity.EXTRA_PROFILE_VERSION,
                Phase13ValidationContract.PROFILE_VERSION);
        intent.putExtra(QualificationActivity.EXTRA_DRIVER_SHA, candidate.sha256);
        intent.putExtra(QualificationActivity.EXTRA_COMPARISON_MODE,
                isTurnipVsTurnip() ? "turnip_vs_turnip" : "system_vs_turnip");
        if (reference != null) {
            intent.putExtra(QualificationActivity.EXTRA_REFERENCE_DRIVER_SHA, reference.sha256);
        }
        startActivity(intent);
    }

    private void chooseDriverZip() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/zip");
        startActivityForResult(picker, REQUEST_IMPORT_DRIVER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_DRIVER || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Toast.makeText(this, getString(R.string.phase13_importing_driver),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                DriverPackage imported = DriverImporter.importZip(this, uri);
                uxPreferences.setQuickCandidateSha(imported.sha256);
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.phase13_import_success,
                            imported.displayName()), Toast.LENGTH_LONG).show();
                    buildUi();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.phase13_import_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }, "home-driver-import").start();
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
                R.string.phase13_reports_detail, view -> {
                    File latest = latestQualification();
                    if (latest == null) showMessage(R.string.phase13_no_result_yet);
                    else openLog(latest);
                }));
        card.addView(AppTheme.divider(this), dividerParams());
        card.addView(actionButton(R.string.phase13_telemetry,
                R.string.phase13_telemetry_detail, view -> open(TelemetryActivity.class)));
        root.addView(card, AppTheme.matchWrap(this, 0, 20));
    }

    private void addAdvancedWorkspace() {
        sectionTitle(R.string.phase13_advanced_tools);
        LinearLayout card = AppTheme.card(this);
        card.addView(actionButton(R.string.phase13_legacy_full_title,
                R.string.phase13_legacy_full_detail,
                view -> openLegacyFull()));
        card.addView(AppTheme.divider(this), dividerParams());
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

    private void openLegacyFull() {
        Intent intent = new Intent(this, QualificationActivity.class);
        intent.putExtra(QualificationActivity.EXTRA_PROFILE_VERSION,
                Phase11Contract.PROFILE_VERSION);
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

    private void openLog(File qualificationFile) {
        Intent intent = new Intent(this, QualificationLogActivity.class);
        intent.putExtra(QualificationLogActivity.EXTRA_QUALIFICATION_PATH,
                qualificationFile.getAbsolutePath());
        startActivity(intent);
    }

    private File latestQualification() {
        File qualifications = new File(getFilesDir(), "qualifications");
        File[] directories = qualifications.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) return null;
        java.util.Arrays.sort(directories,
                java.util.Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            File manifest = new File(directory, "qualification.json");
            if (manifest.isFile()) return manifest;
        }
        return null;
    }

    private DriverPackage selectedCandidate() {
        return candidateSpinner == null ? null
                : driverAt(candidateSpinner.getSelectedItemPosition());
    }

    private DriverPackage selectedReference() {
        return referenceSpinner == null ? null
                : driverAt(referenceSpinner.getSelectedItemPosition());
    }

    private DriverPackage driverAt(int position) {
        return position >= 0 && position < drivers.size() ? drivers.get(position) : null;
    }

    private int indexForSha(String sha, int fallback) {
        for (int index = 0; index < drivers.size(); index++) {
            if (drivers.get(index).sha256.equals(sha)) return index;
        }
        return Math.max(0, Math.min(fallback, Math.max(0, drivers.size() - 1)));
    }

    private void updateDriverDetails(TextView target, DriverPackage driver) {
        if (target == null) return;
        if (driver == null) {
            target.setText(getString(R.string.phase13_import_driver_instruction));
            return;
        }
        String sha = driver.sha256.length() > 16
                ? driver.sha256.substring(0, 16) + "…" : driver.sha256;
        target.setText(driver.displayName() + " · SHA-256 " + sha);
    }

    private boolean isTurnipVsTurnip() {
        return comparisonSpinner != null && comparisonSpinner.getSelectedItemPosition() == 1;
    }

    private void showMessage(int messageRes) {
        new LocalizedAlertDialogBuilder(this)
                .setMessage(getString(messageRes))
                .setPositiveButton(getString(R.string.action_close), null)
                .show();
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
