package com.amaral.driverlab;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Accessible three-level contextual help dialog. */
final class HelpDialog {
    private HelpDialog() {}

    static void show(Activity activity, String identifier) {
        HelpContent.Entry entry = HelpContent.forId(identifier);
        LinearLayout content = AppTheme.vertical(activity);
        content.setPadding(AppTheme.dp(activity, 22), AppTheme.dp(activity, 8),
                AppTheme.dp(activity, 22), AppTheme.dp(activity, 8));
        addSection(activity, content, R.string.help_what_is, entry.what);
        addSection(activity, content, R.string.help_why_matters, entry.why);
        addSection(activity, content, R.string.help_how_interpret, entry.how);
        new LocalizedAlertDialogBuilder(activity)
                .setTitle(activity.getString(entry.title))
                .setView(content)
                .setPositiveButton(activity.getString(R.string.action_close), null)
                .show();
    }

    private static void addSection(Activity activity, LinearLayout root, int title, int body) {
        TextView heading = AppTheme.heading(activity, activity.getString(title), 15);
        root.addView(heading, AppTheme.matchWrap(activity, 12, 6));
        root.addView(AppTheme.body(activity, activity.getString(body)));
    }
}
