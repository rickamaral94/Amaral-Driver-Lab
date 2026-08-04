package com.amaral.driverlab;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

final class LocalizedAlertDialogBuilder extends AlertDialog.Builder {
    private final Context context;

    LocalizedAlertDialogBuilder(Context context) {
        super(context);
        this.context = context;
    }

    @Override public AlertDialog.Builder setTitle(CharSequence title) {
        return super.setTitle(LanguageManager.translateLegacy(context, title));
    }

    @Override public AlertDialog.Builder setMessage(CharSequence message) {
        return super.setMessage(LanguageManager.translateLegacy(context, message));
    }

    @Override public AlertDialog.Builder setPositiveButton(CharSequence text,
                                                            DialogInterface.OnClickListener listener) {
        return super.setPositiveButton(LanguageManager.translateLegacy(context, text), listener);
    }

    @Override public AlertDialog.Builder setNegativeButton(CharSequence text,
                                                            DialogInterface.OnClickListener listener) {
        return super.setNegativeButton(LanguageManager.translateLegacy(context, text), listener);
    }

    @Override public AlertDialog.Builder setNeutralButton(CharSequence text,
                                                           DialogInterface.OnClickListener listener) {
        return super.setNeutralButton(LanguageManager.translateLegacy(context, text), listener);
    }
}
