package com.amaral.driverlab;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Base Activity that applies the persisted locale and localizes legacy programmatic views. */
abstract class LocalizedActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppTheme.apply(this);
    }

    private final Set<TextView> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean translating;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.wrap(newBase));
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        scheduleLocalization();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleLocalization();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        observe(view);
    }

    private void scheduleLocalization() {
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null) observe(decor);
    }

    private void observe(View root) {
        localizeTree(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                () -> localizeTree(root));
    }

    private void localizeTree(View view) {
        if (view instanceof TextView) localizeTextView((TextView) view);
        CharSequence description = view.getContentDescription();
        CharSequence localizedDescription = LanguageManager.translateLegacy(this, description);
        if (localizedDescription != description && localizedDescription != null
                && !localizedDescription.equals(description)) {
            view.setContentDescription(localizedDescription);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                localizeTree(group.getChildAt(index));
            }
        }
    }

    private void localizeTextView(TextView view) {
        applyTranslation(view);
        if (!watched.add(view)) return;
        view.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (!translating) applyTranslation(view);
            }
        });
    }

    private void applyTranslation(TextView view) {
        if (translating) return;
        translating = true;
        try {
            if (!(view instanceof EditText)) {
                CharSequence current = view.getText();
                CharSequence localized = LanguageManager.translateLegacy(this, current);
                if (localized != null && !localized.toString().contentEquals(current)) {
                    view.setText(localized);
                }
            }
            CharSequence hint = view.getHint();
            CharSequence localizedHint = LanguageManager.translateLegacy(this, hint);
            if (localizedHint != null && !localizedHint.toString().contentEquals(hint)) {
                view.setHint(localizedHint);
            }
        } finally {
            translating = false;
        }
    }
}
