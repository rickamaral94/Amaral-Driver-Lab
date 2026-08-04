package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class HtmlReportRenderer {
    private HtmlReportRenderer() {}

    static String render(JSONObject report) {
        JSONObject summary = report.optJSONObject("human_summary");
        JSONObject score = report.optJSONObject("score");
        JSONObject driver = report.optJSONObject("driver");
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Amaral Driver Lab · Full Qualification</title>")
                .append("<style>body{font-family:Arial,sans-serif;margin:32px;max-width:980px;color:#18202a}")
                .append("h1,h2{margin-bottom:8px}.card{border:1px solid #d6dce3;border-radius:12px;padding:18px;margin:14px 0}")
                .append("table{border-collapse:collapse;width:100%}th,td{padding:9px;border-bottom:1px solid #e5e7eb;text-align:left}")
                .append(".good{color:#087830}.bad{color:#b42318}.muted{color:#667085}</style></head><body>");
        html.append("<h1>Amaral Driver Lab</h1><div class=\"muted\">Turnip Full Qualification v1</div>");
        html.append("<div class=\"card\"><h2>").append(escape(summary == null ? "Resultado" : summary.optString("headline")))
                .append("</h2><p>").append(escape(summary == null ? "" : summary.optString("detail")))
                .append("</p><p><b>Driver:</b> ").append(escape(driverLabel(driver))).append("</p>");
        if (score != null) {
            html.append("<p><b>Índice:</b> ").append(number(score.optDouble("overall_index", Double.NaN)))
                    .append(" / 100 &nbsp; <b>Ganho ponderado:</b> ")
                    .append(percent(score.optDouble("weighted_improvement_percent", Double.NaN)))
                    .append(" &nbsp; <b>Confiança:</b> ").append(escape(score.optString("confidence")))
                    .append("</p>");
        }
        html.append("</div>");
        html.append("<div class=\"card\"><h2>Resultados por área</h2><table><tr><th>Área</th><th>Status</th><th>Ganho</th><th>Peso</th></tr>");
        JSONArray categories = score == null ? null : score.optJSONArray("categories");
        if (categories != null) {
            for (int index = 0; index < categories.length(); ++index) {
                JSONObject item = categories.optJSONObject(index);
                if (item == null) continue;
                double value = item.optDouble("improvement_percent", Double.NaN);
                html.append("<tr><td>").append(escape(item.optString("label")))
                        .append("</td><td>").append(escape(item.optString("classification", item.optString("status"))))
                        .append("</td><td class=\"").append(Double.isFinite(value) && value >= 0 ? "good" : "bad")
                        .append("\">").append(percent(value)).append("</td><td>")
                        .append(item.optInt("weight", 0)).append("%</td></tr>");
            }
        }
        html.append("</table></div>");
        html.append("<div class=\"card\"><h2>Gate de compatibilidade</h2>");
        JSONArray reasons = score == null ? null : score.optJSONArray("gate_reasons");
        if (reasons == null || reasons.length() == 0) html.append("<p class=\"good\">Aprovado.</p>");
        else {
            html.append("<ul>");
            for (int index = 0; index < reasons.length(); ++index) {
                html.append("<li class=\"bad\">").append(escape(reasons.optString(index))).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("</div><p class=\"muted\">").append(escape(Phase7Contract.LIMITATION))
                .append("</p></body></html>");
        return html.toString();
    }

    private static String driverLabel(JSONObject driver) {
        if (driver == null) return "desconhecido";
        String name = driver.optString("name", "candidato");
        String version = driver.optString("packageVersion", driver.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.1f", value) : "indisponível";
    }

    private static String percent(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%+.2f%%", value) : "indisponível";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
