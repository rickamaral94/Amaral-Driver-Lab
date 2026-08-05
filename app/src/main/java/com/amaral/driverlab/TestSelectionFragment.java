package com.amaral.driverlab;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Step 2: presents the immutable recommended profile before execution. */
public final class TestSelectionFragment extends Fragment {
    private String comparisonMode = "system_vs_turnip";

    void setComparisonMode(String mode) {
        comparisonMode = mode == null ? "system_vs_turnip" : mode;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = AppTheme.vertical(getActivity());
        root.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_test_step_title), 22));
        root.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_test_step_description)),
                AppTheme.matchWrap(getActivity(), 8, 18));

        LinearLayout recommended = AppTheme.card(getActivity());
        recommended.setBackground(AppTheme.rounded(AmaralColors.SURFACE_ELEVATED, 22,
                AmaralColors.BRAND_PRIMARY, 1, getActivity()));
        recommended.addView(AppTheme.chip(getActivity(),
                getString(R.string.phase13_status_recommended), AmaralColors.SUCCESS),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        recommended.addView(AppTheme.heading(getActivity(),
                getString(R.string.phase13_full_title), 20),
                AppTheme.matchWrap(getActivity(), 12, 7));
        recommended.addView(AppTheme.body(getActivity(),
                getString(R.string.phase13_full_description)));
        recommended.addView(AppTheme.caption(getActivity(),
                getString(R.string.phase13_full_composition)),
                AppTheme.matchWrap(getActivity(), 12, 0));
        root.addView(recommended);

        String comparison = "turnip_vs_turnip".equals(comparisonMode)
                ? getString(R.string.phase13_turnip_comparison_guidance)
                : getString(R.string.phase13_system_comparison_guidance);
        root.addView(AppTheme.body(getActivity(), comparison),
                AppTheme.matchWrap(getActivity(), 16, 0));
        return root;
    }
}
