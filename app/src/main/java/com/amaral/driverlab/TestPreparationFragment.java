package com.amaral.driverlab;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/** Step 3 checklist that makes comparison conditions explicit. */
public final class TestPreparationFragment extends Fragment {
    private final List<CheckBox> checks = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = AppTheme.vertical(getActivity());
        root.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_preparation_title), 22));
        root.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_preparation_description)),
                AppTheme.matchWrap(getActivity(), 8, 16));
        addCheck(root, R.string.phase13_check_close_apps);
        addCheck(root, R.string.phase13_check_performance_mode);
        addCheck(root, R.string.phase13_check_temperature);
        addCheck(root, R.string.phase13_check_not_charging);
        addCheck(root, R.string.phase13_check_ventilation);
        root.addView(AppTheme.caption(getActivity(),
                getString(R.string.phase13_checklist_note)),
                AppTheme.matchWrap(getActivity(), 14, 0));
        return root;
    }

    boolean allChecked() {
        if (checks.isEmpty()) return false;
        for (CheckBox check : checks) if (!check.isChecked()) return false;
        return true;
    }

    private void addCheck(LinearLayout root, int stringRes) {
        CheckBox check = new CheckBox(getActivity());
        check.setText(stringRes);
        check.setTextColor(AmaralColors.TEXT_PRIMARY);
        check.setTextSize(15);
        check.setMinHeight(AppTheme.dp(getActivity(), 52));
        check.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{AmaralColors.BRAND_SECONDARY, AmaralColors.TEXT_MUTED}));
        checks.add(check);
        root.addView(check);
    }
}
