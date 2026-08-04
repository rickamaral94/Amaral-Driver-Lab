package com.amaral.driverlab;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Five-step Phase 13 guided flow. Technical execution remains delegated to Full v3. */
public final class GuidedTestFlowActivity extends LocalizedActivity {
    static final String EXTRA_COMPARISON_MODE = "comparison_mode";
    static final String EXTRA_START_STEP = "start_step";
    private static final int REQUEST_FULL = 1301;

    private final Fragment[] fragments = new Fragment[5];
    private LinearLayout progress;
    private LinearLayout container;
    private Button backButton;
    private Button nextButton;
    private TextView stepLabel;
    private UxPreferenceStore preferences;
    private int currentStep;
    private String comparisonMode;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppTheme.apply(this);
        preferences = new UxPreferenceStore(this);
        comparisonMode = getIntent().getStringExtra(EXTRA_COMPARISON_MODE);
        if (comparisonMode == null) comparisonMode = "system_vs_turnip";
        if (state != null) currentStep = state.getInt("step", 0);
        else if (getIntent().hasExtra(EXTRA_START_STEP)) {
            currentStep = getIntent().getIntExtra(EXTRA_START_STEP, 0);
        } else currentStep = preferences.lastGuidedStep();
        buildUi();
        showStep(currentStep);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("step", currentStep);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FULL) showStep(4);
    }

    private void buildUi() {
        LinearLayout page = AppTheme.vertical(this);
        page.setPadding(dp(18), dp(16), dp(18), dp(28));
        page.setBackgroundColor(AmaralColors.BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button close = AppTheme.iconButton(this, "←", getString(R.string.action_back),
                view -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = AppTheme.vertical(this);
        titles.addView(AppTheme.heading(this, getString(R.string.phase13_guided_title), 21));
        stepLabel = AppTheme.caption(this, "");
        titles.addView(stepLabel, AppTheme.matchWrap(this, 4, 0));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titleParams);
        page.addView(header);

        progress = new LinearLayout(this);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(progress, AppTheme.matchWrap(this, 16, 18));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        container = AppTheme.vertical(this);
        container.setId(View.generateViewId());
        scroll.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        backButton = AppTheme.ghostButton(this, getString(R.string.action_back),
                view -> showStep(currentStep - 1));
        nextButton = AppTheme.primaryButton(this, getString(R.string.action_continue),
                view -> continueFlow());
        navigation.addView(backButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nextParams.setMargins(dp(10), 0, 0, 0);
        navigation.addView(nextButton, nextParams);
        page.addView(navigation, AppTheme.matchWrap(this, 16, 0));
        setContentView(page);
    }

    private void showStep(int requested) {
        currentStep = Math.max(0, Math.min(4, requested));
        preferences.setLastGuidedStep(currentStep);
        Fragment fragment = fragmentFor(currentStep);
        getFragmentManager().beginTransaction()
                .replace(container.getId(), fragment)
                .commitAllowingStateLoss();
        renderProgress();
        stepLabel.setText(getString(R.string.phase13_step_format,
                currentStep + 1, 5, stepName(currentStep)));
        backButton.setEnabled(currentStep > 0);
        if (currentStep == 3) nextButton.setText(R.string.phase13_start_execution);
        else if (currentStep == 4) nextButton.setText(R.string.action_finish);
        else nextButton.setText(R.string.action_continue);
    }

    private Fragment fragmentFor(int step) {
        if (fragments[step] != null) return fragments[step];
        switch (step) {
            case 0:
                fragments[step] = new DriverSelectionFragment();
                break;
            case 1:
                TestSelectionFragment selection = new TestSelectionFragment();
                selection.setComparisonMode(comparisonMode);
                fragments[step] = selection;
                break;
            case 2:
                fragments[step] = new TestPreparationFragment();
                break;
            case 3:
                fragments[step] = new TestProgressFragment();
                break;
            default:
                fragments[step] = new ResultOverviewFragment();
                break;
        }
        return fragments[step];
    }

    private void continueFlow() {
        if (currentStep == 0) {
            DriverSelectionFragment fragment = (DriverSelectionFragment) fragmentFor(0);
            if (!fragment.hasSelection()) {
                actionableDialog(R.string.phase13_no_driver_title,
                        R.string.phase13_no_driver_message);
                return;
            }
            preferences.setGuidedDriverSha(fragment.selectedSha());
            showStep(1);
            return;
        }
        if (currentStep == 2) {
            TestPreparationFragment preparation = (TestPreparationFragment) fragmentFor(2);
            if (!preparation.allChecked()) {
                actionableDialog(R.string.phase13_checklist_incomplete_title,
                        R.string.phase13_checklist_incomplete_message);
                return;
            }
        }
        if (currentStep == 3) {
            Intent intent = new Intent(this, QualificationActivity.class);
            intent.putExtra(QualificationActivity.EXTRA_GUIDED, true);
            intent.putExtra(QualificationActivity.EXTRA_AUTOSTART, true);
            intent.putExtra(QualificationActivity.EXTRA_DRIVER_SHA,
                    preferences.guidedDriverSha());
            startActivityForResult(intent, REQUEST_FULL);
            return;
        }
        if (currentStep == 4) {
            preferences.setLastGuidedStep(0);
            finish();
            return;
        }
        showStep(currentStep + 1);
    }

    private void actionableDialog(int title, int message) {
        new LocalizedAlertDialogBuilder(this)
                .setTitle(getString(title))
                .setMessage(getString(message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(getString(R.string.phase13_open_technical_workspace),
                        (dialog, which) -> startActivity(
                                new Intent(this, AdvancedSettingsActivity.class)))
                .show();
    }

    private void renderProgress() {
        progress.removeAllViews();
        for (int index = 0; index < 5; index++) {
            View segment = new View(this);
            int color = index < currentStep ? AmaralColors.SUCCESS
                    : index == currentStep ? AmaralColors.BRAND_SECONDARY
                    : AmaralColors.SURFACE_HIGHLIGHT;
            segment.setBackground(AppTheme.rounded(color, 100, color, 0, this));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(6), 1f);
            if (index > 0) params.setMargins(dp(5), 0, 0, 0);
            progress.addView(segment, params);
        }
    }

    private String stepName(int step) {
        int[] names = {R.string.phase13_step_driver, R.string.phase13_step_test,
                R.string.phase13_step_preparation, R.string.phase13_step_execution,
                R.string.phase13_step_result};
        return getString(names[step]);
    }

    private int dp(int value) {
        return AppTheme.dp(this, value);
    }
}
