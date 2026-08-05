package com.amaral.driverlab;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Step 1 of the guided flow: fixed stock baseline and one imported candidate. */
public final class DriverSelectionFragment extends Fragment {
    private final List<DriverPackage> drivers = new ArrayList<>();
    private Spinner spinner;
    private TextView details;
    private UxPreferenceStore preferences;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        preferences = new UxPreferenceStore(getActivity());
        LinearLayout root = AppTheme.vertical(getActivity());
        root.setPadding(dp(2), dp(4), dp(2), dp(8));

        root.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_driver_step_title), 22));
        root.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_driver_step_description)),
                AppTheme.matchWrap(getActivity(), 8, 18));

        LinearLayout baseline = AppTheme.card(getActivity());
        baseline.addView(AppTheme.chip(getActivity(),
                getString(R.string.phase13_baseline_chip), AmaralColors.INFO),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        baseline.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_system_driver), 17),
                AppTheme.matchWrap(getActivity(), 12, 5));
        baseline.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_system_driver_detail)));
        root.addView(baseline, AppTheme.matchWrap(getActivity(), 0, 14));

        root.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_candidate_driver), 16),
                AppTheme.matchWrap(getActivity(), 0, 8));
        spinner = new Spinner(getActivity());
        spinner.setMinimumHeight(dp(52));
        root.addView(spinner, AppTheme.matchWrap(getActivity(), 0, 8));
        details = AppTheme.body(getActivity(), "");
        details.setTextIsSelectable(true);
        root.addView(details);
        loadDrivers();
        return root;
    }

    boolean hasSelection() {
        return selectedDriver() != null;
    }

    String selectedSha() {
        DriverPackage driver = selectedDriver();
        return driver == null ? "" : driver.sha256;
    }

    private void loadDrivers() {
        drivers.clear();
        drivers.addAll(DriverCatalog.load(getActivity()));
        List<String> labels = new ArrayList<>();
        if (drivers.isEmpty()) labels.add(getString(R.string.phase13_no_driver_imported));
        for (DriverPackage driver : drivers) labels.add(driver.displayName());
        spinner.setAdapter(new LocalizedArrayAdapter<>(getActivity(),
                android.R.layout.simple_spinner_dropdown_item, labels));
        String stored = preferences.guidedDriverSha();
        int selection = 0;
        for (int index = 0; index < drivers.size(); index++) {
            if (stored.equals(drivers.get(index).sha256)) selection = index;
        }
        spinner.setSelection(selection);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                DriverPackage driver = selectedDriver();
                if (driver != null) preferences.setGuidedDriverSha(driver.sha256);
                updateDetails(driver);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateDetails(selectedDriver());
    }

    private DriverPackage selectedDriver() {
        if (drivers.isEmpty() || spinner == null) return null;
        int position = spinner.getSelectedItemPosition();
        return position >= 0 && position < drivers.size() ? drivers.get(position) : drivers.get(0);
    }

    private void updateDetails(DriverPackage driver) {
        if (details == null) return;
        if (driver == null) {
            details.setText(getString(R.string.phase13_import_driver_instruction));
            return;
        }
        String imported = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(driver.directory.lastModified()));
        details.setText(getString(R.string.phase13_driver_details_format,
                shortSha(driver.sha256), imported,
                driver.minApi <= android.os.Build.VERSION.SDK_INT
                        ? getString(R.string.phase13_compatibility_available)
                        : getString(R.string.phase13_compatibility_warning)));
    }

    private String shortSha(String sha) {
        return sha.length() <= 16 ? sha : sha.substring(0, 16) + "…";
    }

    private int dp(int value) {
        return AppTheme.dp(getActivity(), value);
    }
}
