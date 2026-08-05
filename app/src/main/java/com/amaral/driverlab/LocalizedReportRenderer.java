package com.amaral.driverlab;

import android.content.Context;

import org.json.JSONObject;

/** Locale-aware HTML facade. JSON field names and enum values remain untouched. */
final class LocalizedReportRenderer {
    private LocalizedReportRenderer() {}

    static String render(Context context, JSONObject report) {
        return HtmlReportRenderer.render(LanguageManager.wrap(context), report);
    }
}
