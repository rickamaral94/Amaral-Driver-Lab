package com.amaral.driverlab;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class HtmlReportRenderer {
    private HtmlReportRenderer() {}

    static String render(Context context, JSONObject report) {
        JSONObject summary = report.optJSONObject("human_summary");
        JSONObject score = report.optJSONObject("score");
        JSONObject driver = report.optJSONObject("driver");
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"")
                .append(escape(LanguageManager.effectiveLanguageTag(context)))
                .append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(context.getString(R.string.report_title)))
                .append("</title>")
                .append("<style>body{font-family:Arial,sans-serif;margin:32px;max-width:980px;color:#18202a}")
                .append("h1,h2{margin-bottom:8px}.card{border:1px solid #d6dce3;border-radius:12px;padding:18px;margin:14px 0}")
                .append("table{border-collapse:collapse;width:100%}th,td{padding:9px;border-bottom:1px solid #e5e7eb;text-align:left}")
                .append(".good{color:#087830}.bad{color:#b42318}.muted{color:#667085}</style></head><body>");
        html.append("<h1>Amaral Driver Lab</h1><div class=\"muted\">Turnip Full Qualification v")
                .append(report.optInt("profile_version", 1)).append("</div>");
        String headline = summary == null ? context.getString(R.string.report_result)
                : localized(context, summary.optString("headline"));
        String detail = summary == null ? "" : localized(context, summary.optString("detail"));
        html.append("<div class=\"card\"><h2>").append(escape(headline))
                .append("</h2><p>").append(escape(detail))
                .append("</p><p><b>").append(escape(context.getString(R.string.report_driver)))
                .append(":</b> ").append(escape(driverLabel(context, driver))).append("</p>");
        if (score != null) {
            html.append("<p><b>").append(escape(context.getString(R.string.report_performance)))
                    .append(":</b> ").append(number(context, score.optDouble("performance_index", Double.NaN)))
                    .append(" / 100 &nbsp; <b>")
                    .append(escape(context.getString(R.string.report_compatibility)))
                    .append(":</b> ").append(number(context, score.optDouble("compatibility_index", Double.NaN)))
                    .append(" / 100 &nbsp; <b>")
                    .append(escape(context.getString(R.string.report_weighted_gain)))
                    .append(":</b> ").append(percent(context, score.optDouble("weighted_improvement_percent", Double.NaN)))
                    .append(" &nbsp; <b>").append(escape(context.getString(R.string.report_confidence)))
                    .append(":</b> ").append(escape(localized(context, score.optString("confidence"))))
                    .append("</p>");
        }
        html.append("</div>");
        html.append("<div class=\"card\"><h2>")
                .append(escape(context.getString(R.string.report_results_by_area)))
                .append("</h2><table><tr><th>")
                .append(escape(context.getString(R.string.report_area))).append("</th><th>")
                .append(escape(context.getString(R.string.report_status))).append("</th><th>")
                .append(escape(context.getString(R.string.report_gain))).append("</th><th>")
                .append(escape(context.getString(R.string.report_weight))).append("</th></tr>");
        JSONArray categories = score == null ? null : score.optJSONArray("categories");
        if (categories != null) {
            for (int index = 0; index < categories.length(); ++index) {
                JSONObject item = categories.optJSONObject(index);
                if (item == null) continue;
                double value = item.optDouble("improvement_percent", Double.NaN);
                html.append("<tr><td>").append(escape(localized(context, item.optString("label"))))
                        .append("</td><td>").append(escape(localized(context,
                                item.optString("classification", item.optString("status")))))
                        .append("</td><td class=\"").append(Double.isFinite(value) && value >= 0 ? "good" : "bad")
                        .append("\">").append(percent(context, value)).append("</td><td>")
                        .append(item.optInt("weight", 0)).append("%</td></tr>");
            }
        }
        html.append("</table></div>");
        html.append("<div class=\"card\"><h2>")
                .append(escape(context.getString(R.string.report_compatibility_gate)))
                .append("</h2>");
        JSONArray reasons = score == null ? null : score.optJSONArray("gate_reasons");
        if (reasons == null || reasons.length() == 0) {
            html.append("<p class=\"good\">")
                    .append(escape(context.getString(R.string.report_approved))).append("</p>");
        } else {
            html.append("<ul>");
            for (int index = 0; index < reasons.length(); ++index) {
                // Gate reason identifiers stay technical and stable by design.
                html.append("<li class=\"bad\">").append(escape(reasons.optString(index))).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("</div><p class=\"muted\">")
                .append(escape(localized(context,
                        report.optString("limitations", Phase7Contract.LIMITATION))))
                .append("</p></body></html>");
        return html.toString();
    }

    private static String localized(Context context, String value) {
        return LanguageManager.translateLegacy(context, value).toString();
    }

    private static String driverLabel(Context context, JSONObject driver) {
        if (driver == null) return context.getString(R.string.report_unknown_driver);
        String name = driver.optString("name", context.getString(R.string.report_candidate_driver));
        String version = driver.optString("packageVersion", driver.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }

    private static String number(Context context, double value) {
        return Double.isFinite(value) ? String.format(reportLocale(context), "%.1f", value)
                : context.getString(R.string.report_unavailable);
    }

    private static String percent(Context context, double value) {
        return Double.isFinite(value) ? String.format(reportLocale(context), "%+.2f%%", value)
                : context.getString(R.string.report_unavailable);
    }

    private static Locale reportLocale(Context context) {
        Locale locale = Locale.forLanguageTag(LanguageManager.effectiveLanguageTag(context));
        return locale.getLanguage().isEmpty() ? Locale.ENGLISH : locale;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
