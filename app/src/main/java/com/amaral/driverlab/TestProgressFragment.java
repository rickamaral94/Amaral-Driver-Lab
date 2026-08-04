package com.amaral.driverlab;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Comparator;

/** Step 4 segmented execution summary. The technical runner remains QualificationActivity. */
public final class TestProgressFragment extends Fragment {
    private LinearLayout sections;
    private TextView state;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        LinearLayout root = AppTheme.vertical(getActivity());
        root.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_execution_title), 22));
        root.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_execution_description)),
                AppTheme.matchWrap(getActivity(), 8, 16));
        sections = AppTheme.vertical(getActivity());
        root.addView(sections);
        state = AppTheme.body(getActivity(), getString(R.string.phase13_execution_ready));
        root.addView(state, AppTheme.matchWrap(getActivity(), 16, 0));
        refresh();
        return root;
    }

    void refresh() {
        if (sections == null || getActivity() == null) return;
        sections.removeAllViews();
        File latest = latestQualification();
        int visual = 0;
        int performance = 0;
        int diagnostics = 0;
        int soak = 0;
        int completed = 0;
        int total = QualificationProfile.steps().size();
        String executionState = "pending";
        if (latest != null) {
            try {
                JSONObject manifest = QualificationStore.load(latest);
                completed = QualificationStore.countStatus(manifest, "completed");
                executionState = manifest.optJSONObject("execution") == null ? "pending"
                        : manifest.optJSONObject("execution").optString("state", "pending");
                JSONArray steps = manifest.optJSONArray("steps");
                if (steps != null) {
                    for (int index = 0; index < steps.length(); index++) {
                        JSONObject step = steps.optJSONObject(index);
                        if (step == null || !"completed".equals(step.optString("status"))) continue;
                        String id = step.optString("step_id");
                        if (isVisual(id)) visual++;
                        else if ("deep_diagnostics".equals(id)) diagnostics++;
                        else if ("short_soak".equals(id)) soak++;
                        else performance++;
                    }
                }
            } catch (Exception ignored) {
                executionState = "pending";
            }
        }
        addSection(R.string.phase13_progress_visual, visual, 5);
        addSection(R.string.phase13_progress_performance, performance, 8);
        addSection(R.string.phase13_progress_diagnostics, diagnostics, 1);
        addSection(R.string.phase13_progress_soak, soak, 1);
        if (state != null) state.setText(getString(R.string.phase13_progress_state_format,
                Math.min(completed, total), total, localizedState(executionState)));
    }

    private void addSection(int labelRes, int sectionCompleted, int size) {
        LinearLayout card = AppTheme.card(getActivity());
        LinearLayout row = new LinearLayout(getActivity());
        TextView label = AppTheme.heading(getActivity(), getString(labelRes), 15);
        sectionCompleted = Math.max(0, Math.min(size, sectionCompleted));
        TextView count = AppTheme.caption(getActivity(), sectionCompleted + "/" + size);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(count);
        card.addView(row);
        ProgressBar progress = new ProgressBar(getActivity(), null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(size);
        progress.setProgress(sectionCompleted);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                sectionCompleted == size ? AmaralColors.SUCCESS : AmaralColors.BRAND_SECONDARY));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                AmaralColors.SURFACE_HIGHLIGHT));
        card.addView(progress, AppTheme.matchWrap(getActivity(), 10, 0));
        sections.addView(card, AppTheme.matchWrap(getActivity(), 0, 10));
    }


    private boolean isVisual(String id) {
        return "correctness_pre".equals(id)
                || "visual_geometry".equals(id)
                || "visual_materials".equals(id)
                || "visual_postprocess".equals(id)
                || "correctness_post".equals(id);
    }

    private File latestQualification() {
        File root = new File(getActivity().getFilesDir(), "qualifications");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) return null;
        java.util.Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            File manifest = new File(directory, "qualification.json");
            if (manifest.isFile()) return manifest;
        }
        return null;
    }

    private String localizedState(String value) {
        if (value.startsWith("completed")) return getString(R.string.phase13_status_completed);
        if (value.contains("failed")) return getString(R.string.phase13_status_blocked);
        if (value.contains("paused")) return getString(R.string.phase13_status_paused);
        if (value.contains("running")) return getString(R.string.phase13_status_running);
        return getString(R.string.phase13_status_ready);
    }
}
