package com.amaral.driverlab;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Programmatic design system used by Phase 13 screens. */
final class AppTheme {
    private AppTheme() {}

    static void apply(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        window.setStatusBarColor(AmaralColors.BACKGROUND);
        window.setNavigationBarColor(AmaralColors.BACKGROUND);
        window.getDecorView().setBackgroundColor(AmaralColors.BACKGROUND);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
    }

    static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = vertical(context);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(rounded(AmaralColors.SURFACE, 22, AmaralColors.BORDER, 1, context));
        card.setElevation(dp(context, 2));
        return card;
    }

    static TextView heading(Context context, CharSequence value, float sizeSp) {
        TextView view = text(context, value, sizeSp, AmaralColors.TEXT_PRIMARY);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        return view;
    }

    static TextView body(Context context, CharSequence value) {
        TextView view = text(context, value, 14, AmaralColors.TEXT_SECONDARY);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    static TextView caption(Context context, CharSequence value) {
        return text(context, value, 12, AmaralColors.TEXT_MUTED);
    }

    static TextView text(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView chip(Context context, CharSequence value, int color) {
        TextView chip = text(context, value, 12, color);
        chip.setTypeface(Typeface.create("sans", Typeface.BOLD));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        chip.setBackground(rounded(withAlpha(color, 38), 100, color, 1, context));
        return chip;
    }

    static Button primaryButton(Context context, CharSequence label, View.OnClickListener listener) {
        return button(context, label, AmaralColors.BRAND_PRIMARY, AmaralColors.TEXT_PRIMARY, listener);
    }

    static Button secondaryButton(Context context, CharSequence label, View.OnClickListener listener) {
        return button(context, label, AmaralColors.SURFACE_HIGHLIGHT,
                AmaralColors.TEXT_PRIMARY, listener);
    }

    static Button ghostButton(Context context, CharSequence label, View.OnClickListener listener) {
        Button button = button(context, label, AmaralColors.SURFACE,
                AmaralColors.TEXT_PRIMARY, listener);
        button.setBackground(rounded(AmaralColors.SURFACE, 16, AmaralColors.BORDER, 1, context));
        return button;
    }

    static Button iconButton(Context context, CharSequence label, CharSequence description,
                             View.OnClickListener listener) {
        Button button = ghostButton(context, label, listener);
        button.setContentDescription(description);
        button.setMinWidth(dp(context, 48));
        return button;
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(AmaralColors.BORDER);
        return divider;
    }

    static GradientDrawable rounded(int fill, int radiusDp, int stroke, int strokeDp,
                                    Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(context, strokeDp), stroke);
        return drawable;
    }

    static LinearLayout.LayoutParams matchWrap(Context context, int topDp, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, topDp), 0, dp(context, bottomDp));
        return params;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Button button(Context context, CharSequence label, int background,
                                 int textColor, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setTextColor(textColor);
        button.setMinHeight(dp(context, 52));
        button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setBackground(rounded(background, 16, background, 0, context));
        button.setOnClickListener(listener);
        return button;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00ffffff);
    }
}
