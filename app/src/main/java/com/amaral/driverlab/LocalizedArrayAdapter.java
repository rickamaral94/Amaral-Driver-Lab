package com.amaral.driverlab;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

final class LocalizedArrayAdapter<T> extends ArrayAdapter<T> {
    LocalizedArrayAdapter(Context context, int resource, T[] objects) {
        super(context, resource, objects);
    }

    LocalizedArrayAdapter(Context context, int resource, List<T> objects) {
        super(context, resource, objects);
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        return localize(super.getView(position, convertView, parent));
    }

    @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return localize(super.getDropDownView(position, convertView, parent));
    }

    private View localize(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setText(LanguageManager.translateLegacy(getContext(), text.getText()));
        }
        return view;
    }
}
