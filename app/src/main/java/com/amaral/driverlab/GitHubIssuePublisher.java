package com.amaral.driverlab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

final class GitHubIssuePublisher {
    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private GitHubIssuePublisher() {}

    static String publish(String token, String owner, String repository,
                          JSONObject report) throws Exception {
        validateRepository(owner, repository);
        JSONObject payload = new JSONObject();
        payload.put("title", issueTitle(report));
        payload.put("body", issueBody(report, true));

        URL endpoint = new URL("https://api.github.com/repos/" + owner + "/"
                + repository + "/issues");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "Amaral-Driver-Lab/" + BuildConfig.VERSION_NAME);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(input);
        connection.disconnect();
        if (status != 201) {
            throw new IllegalStateException("GitHub respondeu HTTP " + status + ": " + response);
        }
        return new JSONObject(response).getString("html_url");
    }

    static void openDraft(Activity activity, String owner, String repository,
                          JSONObject report) throws Exception {
        validateRepository(owner, repository);
        String body = issueBody(report, false);
        if (body.length() > 1800) body = body.substring(0, 1800) + "\n\n_Result JSON salvo no aparelho._";
        Uri uri = Uri.parse("https://github.com/" + owner + "/" + repository + "/issues/new")
                .buildUpon()
                .appendQueryParameter("title", issueTitle(report))
                .appendQueryParameter("body", body)
                .build();
        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    static String issueTitle(JSONObject report) {
        JSONObject host = report.optJSONObject("host_device");
        String model = host == null ? Build.MODEL : host.optString("model", Build.MODEL);
        JSONObject candidate = report.optJSONObject("candidate");
        String driver = candidate == null ? "system"
                : candidate.optString("name", "candidate") + " "
                + candidate.optString("packageVersion", candidate.optString("driverVersion", ""));
        String workloadId = report.optString("workload_id", WorkloadContract.TRANSFER_ID);
        String suffix = "";
        if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
            String verdict = report.optString("verdict", "completed_no_reference");
            suffix = " · " + ("passed_render_correctness".equals(verdict) ? "render PASS"
                    : "failed_render_correctness".equals(verdict) ? "render FAIL"
                    : "failed_execution".equals(verdict) ? "execution FAIL" : "no A/B reference");
        } else {
            JSONObject summary = report.optJSONObject("summary");
            double delta = summary == null ? Double.NaN
                    : summary.optDouble("candidate_vs_system_percent", Double.NaN);
            if (Double.isFinite(delta)) suffix = String.format(Locale.US, " · %+.1f%%", delta);
        }
        String title = "[driver-lab] " + model + " · " + driver.trim() + suffix;
        return title.length() > 240 ? title.substring(0, 240) : title;
    }

    static String issueBody(JSONObject report, boolean includeJson) {
        StringBuilder body = new StringBuilder();
        JSONObject host = report.optJSONObject("host_device");
        JSONObject candidate = report.optJSONObject("candidate");
        JSONObject summary = report.optJSONObject("summary");
        String workloadId = report.optString("workload_id", WorkloadContract.TRANSFER_ID);
        body.append("## Amaral Driver Lab\n\n");
        body.append("| Campo | Valor |\n|---|---|\n");
        body.append("| Suite | `").append(table(report.optString("suite_id"))).append("` |\n");
        body.append("| Aparelho | ").append(table(host == null ? Build.MODEL
                : host.optString("manufacturer") + " " + host.optString("model"))).append(" |\n");
        body.append("| Android | ").append(table(host == null ? Build.VERSION.RELEASE
                : host.optString("android_release") + " / API " + host.optInt("android_sdk"))).append(" |\n");
        body.append("| Candidato | ").append(table(candidate == null ? "—"
                : candidate.optString("name") + " " + candidate.optString("packageVersion"))).append(" |\n");
        body.append("| SHA-256 | `").append(candidate == null ? "—"
                : table(candidate.optString("sha256"))).append("` |\n");
        body.append("| Workload | `").append(table(workloadId)).append("` v")
                .append(report.optInt("workload_version", 1)).append(" |\n");
        body.append("| Método | ").append(table(report.optString("mode")))
                .append(", ").append(report.optInt("rounds")).append(" rodada(s), ordem ")
                .append(table(report.optString("order_policy"))).append(" |\n");
        body.append("| Veredito | `").append(table(report.optString("verdict", "—")))
                .append("` |\n");
        if (summary != null && WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
            double match = summary.optDouble("pixel_match_percent", Double.NaN);
            body.append("| Pixels compatíveis | ")
                    .append(Double.isFinite(match)
                            ? String.format(Locale.US, "%.6f%%", match) : "—")
                    .append(" |\n");
            body.append("| Máx. blocos divergentes | ")
                    .append(summary.has("maximum_divergent_block_count")
                            ? summary.opt("maximum_divergent_block_count") : "—")
                    .append(" |\n");
        } else if (summary != null) {
            double delta = summary.optDouble("candidate_vs_system_percent", Double.NaN);
            body.append("| Delta candidato × sistema | ")
                    .append(Double.isFinite(delta) ? String.format(Locale.US, "%+.2f%%", delta) : "—")
                    .append(" |\n");
            body.append("| Fases com falha | ").append(summary.optInt("failed_phases")).append(" |\n");
        }
        JSONObject capabilityDiff = report.optJSONObject("capability_diff");
        if (capabilityDiff != null) {
            JSONArray gained = capabilityDiff.optJSONArray("extensions_gained");
            JSONArray lost = capabilityDiff.optJSONArray("extensions_lost");
            body.append("| Extensões ganhas/perdidas | +")
                    .append(gained == null ? 0 : gained.length()).append(" / -")
                    .append(lost == null ? 0 : lost.length()).append(" |\n");
        }
        JSONArray failures = report.optJSONArray("failure_catalog");
        body.append("| Eventos de falha | ").append(failures == null ? 0 : failures.length())
                .append(" |\n");

        body.append("\n### Validade\n\n");
        JSONArray warnings = report.optJSONArray("validity_warnings");
        if (warnings == null || warnings.length() == 0) {
            body.append("- Sem alertas automáticos.\n");
        } else {
            for (int index = 0; index < warnings.length(); ++index) {
                body.append("- ").append(warnings.optString(index)).append("\n");
            }
        }
        body.append("\n> ").append(report.optString("metric_limitations",
                WorkloadContract.limitationFor(workloadId))).append("\n");
        if (capabilityDiff != null) {
            body.append("\n### Diff de capacidades\n\n");
            body.append("- ").append(capabilityDiff.optString("summary", "Sem resumo.")).append("\n");
            appendArray(body, "Extensões ganhas", capabilityDiff.optJSONArray("extensions_gained"));
            appendArray(body, "Extensões perdidas", capabilityDiff.optJSONArray("extensions_lost"));
            appendArray(body, "Features ganhas", capabilityDiff.optJSONArray("features_gained"));
            appendArray(body, "Features perdidas", capabilityDiff.optJSONArray("features_lost"));
        }
        if (includeJson) {
            String encoded;
            try {
                encoded = report.toString(2);
            } catch (Exception ignored) {
                encoded = report.toString();
            }
            boolean truncated = encoded.length() > 45_000;
            if (truncated) encoded = encoded.substring(0, 45_000);
            body.append("\n<details><summary>Resultado JSON")
                    .append(truncated ? " (parcial)" : "")
                    .append("</summary>\n\n```json\n")
                    .append(encoded)
                    .append("\n```\n</details>\n");
        }
        body.append("\n_Gerado por Amaral Driver Lab ").append(BuildConfig.VERSION_NAME).append("._");
        return body.toString();
    }

    private static void appendArray(StringBuilder body, String label, JSONArray values) {
        if (values == null || values.length() == 0) return;
        body.append("- ").append(label).append(": ");
        int limit = Math.min(values.length(), 20);
        for (int index = 0; index < limit; ++index) {
            if (index > 0) body.append(", ");
            body.append('`').append(values.optString(index)).append('`');
        }
        if (values.length() > limit) body.append(" … +").append(values.length() - limit);
        body.append("\n");
    }

    private static void validateRepository(String owner, String repository) {
        if (owner == null || repository == null
                || !REPOSITORY_PART.matcher(owner.trim()).matches()
                || !REPOSITORY_PART.matcher(repository.trim()).matches()) {
            throw new IllegalArgumentException("Owner/repositório do GitHub inválido");
        }
    }

    private static String table(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
