package com.amaral.driverlab;

import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Comparator;
import java.util.Locale;

/** Step 5: human conclusion first, technical details progressively disclosed. */
public final class ResultOverviewFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = AppTheme.vertical(getActivity());
        root.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_result_title), 22));
        root.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_result_description)),
                AppTheme.matchWrap(getActivity(), 8, 16));
        render(root);
        return root;
    }

    private void render(LinearLayout root) {
        File latest = latestQualification();
        if (latest == null) {
            root.addView(AppTheme.body(getActivity(),
                    getString(R.string.phase13_no_result_yet)));
            return;
        }
        try {
            JSONObject manifest = QualificationStore.load(latest);
            JSONObject report = manifest.optJSONObject("report");
            if (report == null) {
                root.addView(AppTheme.body(getActivity(),
                        getString(R.string.phase13_result_in_progress)));
                return;
            }
            JSONObject summary = report.optJSONObject("human_summary");
            JSONObject score = report.optJSONObject("score");
            String winner = score == null ? "inconclusive" : score.optString("winner", "inconclusive");
            LinearLayout card = AppTheme.card(getActivity());
            LinearLayout top = new LinearLayout(getActivity());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(AppTheme.heading(getActivity(), getString(R.string.phase13_result_general), 16),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(AppTheme.chip(getActivity(), recommendationLabel(winner),
                    recommendationColor(winner)));
            card.addView(top);
            card.addView(AppTheme.heading(getActivity(), summary == null
                    ? getString(R.string.phase13_result_inconclusive)
                    : summary.optString("headline", getString(R.string.phase13_result_inconclusive)), 21),
                    AppTheme.matchWrap(getActivity(), 14, 8));
            if (summary != null && !summary.optString("detail").isEmpty()) {
                card.addView(AppTheme.body(getActivity(), summary.optString("detail")));
            }
            double performance = score == null ? Double.NaN
                    : score.optDouble("performance_index", Double.NaN);
            double compatibility = score == null ? Double.NaN
                    : score.optDouble("compatibility_index", Double.NaN);
            double gain = score == null ? Double.NaN
                    : score.optDouble("weighted_improvement_percent", Double.NaN);
            card.addView(metricRow(R.string.report_performance, format(performance) + " / 100",
                    "performance"), AppTheme.matchWrap(getActivity(), 16, 0));
            card.addView(metricRow(R.string.report_compatibility, format(compatibility) + " / 100",
                    "compatibility"));
            card.addView(metricRow(R.string.report_weighted_gain, formatPercent(gain),
                    "performance"));
            JSONArray reasons = score == null ? null : score.optJSONArray("gate_reasons");
            if (reasons != null && reasons.length() > 0) {
                TextView heading = AppTheme.heading(getActivity(),
                        getString(R.string.phase13_problems_found), 15);
                card.addView(heading, AppTheme.matchWrap(getActivity(), 16, 6));
                for (int index = 0; index < reasons.length(); index++) {
                    card.addView(AppTheme.body(getActivity(), "• " + reasons.optString(index)));
                }
            } else {
                card.addView(AppTheme.body(getActivity(),
                        "✓ " + getString(R.string.phase13_no_blocking_problem)),
                        AppTheme.matchWrap(getActivity(), 14, 0));
            }
            root.addView(card);
        } catch (Exception error) {
            root.addView(AppTheme.body(getActivity(),
                    getString(R.string.phase13_result_read_error, error.getMessage())));
        }
    }

    private View metricRow(int labelRes, String value, String helpId) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = AppTheme.body(getActivity(), getString(labelRes));
        TextView metric = AppTheme.heading(getActivity(), value, 15);
        TextView help = AppTheme.chip(getActivity(), "?", AmaralColors.INFO);
        help.setContentDescription(getString(R.string.phase13_help_content_description,
                getString(labelRes)));
        help.setOnClickListener(view -> HelpDialog.show(getActivity(), helpId));
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(metric);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        helpParams.setMargins(AppTheme.dp(getActivity(), 10), 0, 0, 0);
        row.addView(help, helpParams);
        row.setPadding(0, AppTheme.dp(getActivity(), 7), 0, AppTheme.dp(getActivity(), 7));
        return row;
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

    private String recommendationLabel(String winner) {
        if (winner.contains("candidate")) return getString(R.string.phase13_status_recommended);
        if (winner.contains("system")) return getString(R.string.phase13_status_not_recommended);
        if (winner.contains("tie")) return getString(R.string.phase13_status_tie);
        return getString(R.string.phase13_status_inconclusive);
    }

    private int recommendationColor(String winner) {
        if (winner.contains("candidate")) return AmaralColors.SUCCESS;
        if (winner.contains("system")) return AmaralColors.ERROR;
        if (winner.contains("tie")) return AmaralColors.INFO;
        return AmaralColors.UNAVAILABLE;
    }

    private String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.1f", value)
                : getString(R.string.report_unavailable);
    }

    private String formatPercent(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%+.2f%%", value)
                : getString(R.string.report_unavailable);
    }
}
