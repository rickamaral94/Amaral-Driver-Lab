package com.amaral.driverlab;

import android.app.Activity;
import android.app.AlertDialog;

final class LanguageSelectorDialog {
    private LanguageSelectorDialog() {}

    static void show(Activity activity) {
        LanguagePreference[] values = LanguagePreference.values();
        String[] labels = new String[values.length];
        LanguagePreference current = LanguageManager.current(activity);
        int checked = 0;
        for (int index = 0; index < values.length; index++) {
            LanguagePreference value = values[index];
            labels[index] = value.flag + "  " + LanguageManager.get(activity, value.labelRes);
            if (value == current) checked = index;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.language_selector_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    LanguageManager.set(activity, values[which]);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
