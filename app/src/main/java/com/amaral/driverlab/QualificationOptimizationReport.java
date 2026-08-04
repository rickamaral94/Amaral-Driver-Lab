package com.amaral.driverlab;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Builds the compact, issue-ready evidence needed to optimize Turnip packages. */
final class QualificationOptimizationReport {
    static final int FORMAT_VERSION = 1;
    private static final double PRACTICAL_MARGIN_PERCENT = 3.0;

    private QualificationOptimizationReport() {}

    static JSONObject build(JSONObject manifest, JSONArray scoredSteps,
                            JSONObject hardware, JSONObject score) throws Exception {
        JSONArray metrics = new JSONArray();
        JSONArray loaderAudit = new JSONArray();
        JSONObject best = null;
        JSONObject worst = null;

        int profileVersion = manifest.getJSONObject("profile").getInt("profile_version");
        for (int index = 0; index < scoredSteps.length(); index++) {
            JSONObject scored = scoredSteps.optJSONObject(index);
            if (scored == null || !"completed".equals(scored.optString("status"))) continue;
            JSONObject suite = scored.optJSONObject("report");
            if (suite == null) continue;
            String stepId = scored.optString("step_id", "unknown");
            QualificationProfile.Step definition = QualificationProfile.step(profileVersion, stepId);
            appendLoaderAudit(loaderAudit, stepId, suite.optJSONArray("phases"));
            JSONObject metric = metricFor(definition, suite);
            if (metric == null) continue;
            metrics.put(metric);
            double delta = metric.optDouble("candidate_improvement_percent", Double.NaN);
            if (Double.isFinite(delta)) {
                if (best == null || delta > best.optDouble("candidate_improvement_percent",
                        Double.NEGATIVE_INFINITY)) best = metric;
                if (worst == null || delta < worst.optDouble("candidate_improvement_percent",
                        Double.POSITIVE_INFINITY)) worst = metric;
            }
        }

        JSONObject preflight = manifest.optJSONObject("preflight");
        JSONObject device = preflight == null ? null : preflight.optJSONObject("device");
        JSONObject target = hardwareTarget(hardware, device);
        return new JSONObject()
                .put("format_version", FORMAT_VERSION)
                .put("hardware_target", target)
                .put("loader_audit", loaderAudit)
                .put("metrics", metrics)
                .put("best_area", best == null ? JSONObject.NULL : finding(best))
                .put("worst_area", worst == null ? JSONObject.NULL : finding(worst))
                .put("score_summary", score == null ? JSONObject.NULL : new JSONObject()
                        .put("overall_index", score.opt("overall_index"))
                        .put("performance_index", score.opt("performance_index"))
                        .put("compatibility_index", score.opt("compatibility_index"))
                        .put("weighted_improvement_percent",
                                score.opt("weighted_improvement_percent"))
                        .put("confidence", score.optString("confidence", "unknown")))
                .put("environment", environment(manifest));
    }

    private static JSONObject metricFor(QualificationProfile.Step definition,
                                        JSONObject suite) throws Exception {
        String stepId = definition == null ? "unknown" : definition.stepId;
        String label = definition == null ? stepId : definition.label;
        String workloadId = suite.optString("workload_id", "unknown");
        JSONObject item = new JSONObject()
                .put("step_id", stepId)
                .put("label", label)
                .put("workload_id", workloadId)
                .put("verdict", suite.optString("verdict", "unknown"));

        if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
            JSONObject render = suite.optJSONObject("render_correctness");
            if (render == null) return item.put("kind", "correctness")
                    .put("status", "unavailable");
            double match = render.optDouble("pixel_match_percent", Double.NaN);
            boolean passed = render.optBoolean("passed", false);
            return item.put("kind", "correctness")
                    .put("metric", "pixel_match_percent")
                    .put("unit", "%")
                    .put("reference_value", 100.0)
                    .put("candidate_value", finite(match))
                    .put("maximum_divergent_blocks",
                            render.opt("maximum_divergent_block_count"))
                    .put("comparison_count", render.optInt("comparison_count", 0))
                    .put("classification", passed ? "passed" : "failed")
                    .put("candidate_improvement_percent", JSONObject.NULL);
        }

        JSONObject analysis = suite.optJSONObject("statistical_analysis");
        JSONObject summary = suite.optJSONObject("summary");
        if (analysis == null && summary == null) return null;
        String metricName = analysis == null
                ? (summary == null ? "unknown" : summary.optString("primary_metric", "unknown"))
                : analysis.optString("primary_metric", "unknown");
        boolean lowerIsBetter = analysis != null
                ? analysis.optBoolean("lower_is_better", false)
                : WorkloadContract.lowerIsBetter(workloadId);
        JSONObject referenceStats = analysis == null
                ? (summary == null ? null : summary.optJSONObject("system"))
                : analysis.optJSONObject("system");
        JSONObject candidateStats = analysis == null
                ? (summary == null ? null : summary.optJSONObject("candidate"))
                : analysis.optJSONObject("candidate");
        double delta = analysis == null
                ? (summary == null ? Double.NaN
                : summary.optDouble("candidate_improvement_percent", Double.NaN))
                : analysis.optDouble("median_paired_improvement_percent", Double.NaN);
        String classification = analysis == null
                ? classify(delta) : analysis.optString("classification", classify(delta));
        JSONObject ci = analysis == null ? null
                : analysis.optJSONObject("confidence_interval_95_percent");
        return item.put("kind", "performance")
                .put("metric", metricName)
                .put("unit", metricUnit(metricName))
                .put("lower_is_better", lowerIsBetter)
                .put("reference", compactStats(referenceStats))
                .put("candidate", compactStats(candidateStats))
                .put("candidate_improvement_percent", finite(delta))
                .put("classification", classification)
                .put("paired_sample_count", analysis == null ? 0
                        : analysis.optInt("paired_sample_count", 0))
                .put("wins", analysis == null ? 0 : analysis.optInt("wins", 0))
                .put("ties", analysis == null ? 0 : analysis.optInt("ties", 0))
                .put("losses", analysis == null ? 0 : analysis.optInt("losses", 0))
                .put("confidence_interval_95_percent", ci == null ? JSONObject.NULL
                        : new JSONObject().put("lower", ci.opt("lower"))
                        .put("upper", ci.opt("upper")));
    }

    private static JSONObject compactStats(JSONObject source) throws Exception {
        if (source == null) return new JSONObject()
                .put("sample_count", 0)
                .put("median", JSONObject.NULL)
                .put("mean", JSONObject.NULL)
                .put("p95", JSONObject.NULL)
                .put("p99", JSONObject.NULL)
                .put("coefficient_of_variation_percent", JSONObject.NULL);
        return new JSONObject()
                .put("sample_count", source.optInt("sample_count", 0))
                .put("median", first(source, "median", "median_transfer_payload_gib_s"))
                .put("mean", first(source, "mean", "mean_transfer_payload_gib_s"))
                .put("p95", source.opt("p95"))
                .put("p99", source.opt("p99"))
                .put("coefficient_of_variation_percent",
                        source.opt("coefficient_of_variation_percent"));
    }

    private static Object first(JSONObject source, String primary, String fallback) {
        Object value = source.opt(primary);
        return value == null ? source.opt(fallback) : value;
    }

    private static void appendLoaderAudit(JSONArray output, String stepId,
                                          JSONArray phases) throws Exception {
        if (phases == null) return;
        for (int index = 0; index < phases.length(); index++) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null) continue;
            output.put(new JSONObject()
                    .put("step_id", stepId)
                    .put("round", phase.optInt("round", -1))
                    .put("role", phase.optString("driver_role",
                            DriverExecutionIdentity.isCandidateArm(phase)
                                    ? DriverExecutionIdentity.ROLE_CANDIDATE
                                    : DriverExecutionIdentity.ROLE_REFERENCE))
                    .put("mode", phase.optString("driver_mode", "unknown"))
                    .put("driver_name", phase.opt("driver_display_name"))
                    .put("driver_sha256", phase.opt("driver_sha256"))
                    .put("success", phase.optBoolean("success", false)));
        }
    }

    private static JSONObject hardwareTarget(JSONObject hardware, JSONObject device)
            throws Exception {
        JSONObject source = hardware == null ? new JSONObject() : hardware;
        JSONObject raw = device == null ? new JSONObject() : device;
        String manufacturer = source.optString("manufacturer",
                raw.optString("manufacturer", Build.MANUFACTURER));
        String model = source.optString("model", raw.optString("model", Build.MODEL));
        String soc = source.optString("soc_model", raw.optString("soc_model", "unknown"));
        String gpu = source.optString("gpu_model", "unknown");
        return new JSONObject()
                .put("console", manufacturer + " " + model)
                .put("manufacturer", manufacturer)
                .put("model", model)
                .put("soc_manufacturer", source.optString("soc_manufacturer",
                        raw.optString("soc_manufacturer", "unknown")))
                .put("soc_model", soc)
                .put("gpu_model", gpu)
                .put("android_release", raw.optString("android_release", Build.VERSION.RELEASE))
                .put("android_sdk", raw.optInt("android_sdk", Build.VERSION.SDK_INT))
                .put("device", raw.optString("device", "unknown"))
                .put("product", raw.optString("product", "unknown"))
                .put("board", raw.optString("board", "unknown"))
                .put("hardware", raw.optString("hardware", "unknown"))
                .put("device_key", source.optString("device_key", "unknown"))
                .put("public_hardware_key",
                        source.optString("public_hardware_key", normalize(soc) + "/" + normalize(gpu)));
    }

    private static JSONObject environment(JSONObject manifest) throws Exception {
        JSONObject preflight = manifest.optJSONObject("preflight");
        JSONObject evaluation = preflight == null ? null : preflight.optJSONObject("evaluation");
        JSONObject report = manifest.optJSONObject("report");
        JSONObject comparison = report == null ? null
                : report.optJSONObject("environment_comparison");
        return new JSONObject()
                .put("eligible_to_start", evaluation == null
                        ? JSONObject.NULL : evaluation.opt("eligible_to_start"))
                .put("blockers", evaluation == null ? JSONObject.NULL : evaluation.opt("blockers"))
                .put("environment_comparison", comparison == null
                        ? JSONObject.NULL : comparison);
    }

    private static JSONObject finding(JSONObject metric) throws Exception {
        return new JSONObject()
                .put("step_id", metric.optString("step_id"))
                .put("label", metric.optString("label"))
                .put("metric", metric.optString("metric"))
                .put("candidate_improvement_percent",
                        metric.opt("candidate_improvement_percent"))
                .put("classification", metric.optString("classification"));
    }

    static String hardwareDisplay(JSONObject manifest) {
        JSONObject target = target(manifest);
        return "Console: " + target.optString("console", "—")
                + "\nSoC: " + target.optString("soc_model", "—")
                + "\nGPU: " + target.optString("gpu_model", "—")
                + "\nAndroid: " + target.optString("android_release", "—")
                + " (API " + target.optInt("android_sdk", -1) + ")"
                + "\nDevice/Product/Board: " + target.optString("device", "—") + " / "
                + target.optString("product", "—") + " / "
                + target.optString("board", "—")
                + "\nChave: " + target.optString("public_hardware_key", "—");
    }

    static String metricsDisplay(JSONObject manifest) {
        JSONArray metrics = metrics(manifest);
        if (metrics.length() == 0) return "Sem métricas consolidadas.";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < metrics.length(); i++) {
            JSONObject item = metrics.optJSONObject(i);
            if (item == null) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(item.optString("label", item.optString("step_id"))).append("\n");
            if ("correctness".equals(item.optString("kind"))) {
                out.append("Pixel match: ").append(number(item.opt("candidate_value"), 4))
                        .append("% · ").append(item.optString("classification", "—"));
            } else {
                JSONObject ref = item.optJSONObject("reference");
                JSONObject cand = item.optJSONObject("candidate");
                out.append("Referência: ").append(number(ref == null ? null : ref.opt("median"), 3))
                        .append(" · Candidato: ")
                        .append(number(cand == null ? null : cand.opt("median"), 3))
                        .append(" ").append(item.optString("unit", ""))
                        .append(" · Delta: ")
                        .append(signed(item.opt("candidate_improvement_percent")))
                        .append(" · ").append(item.optString("classification", "—"));
            }
        }
        return out.toString();
    }

    static String hardwareMarkdown(JSONObject manifest) {
        JSONObject t = target(manifest);
        return "### Alvo de hardware\n\n| Campo | Valor |\n|---|---|\n"
                + row("Console/dispositivo", t.optString("console", "—"))
                + row("Fabricante / modelo", t.optString("manufacturer", "—") + " / "
                        + t.optString("model", "—"))
                + row("SoC", t.optString("soc_manufacturer", "—") + " "
                        + t.optString("soc_model", "—"))
                + row("GPU", t.optString("gpu_model", "—"))
                + row("Android", t.optString("android_release", "—") + " / API "
                        + t.optInt("android_sdk", -1))
                + row("Device / Product / Board", t.optString("device", "—") + " / "
                        + t.optString("product", "—") + " / " + t.optString("board", "—"))
                + row("Hardware key", "`" + t.optString("public_hardware_key", "—") + "`")
                + "\n";
    }

    static String loaderMarkdown(JSONObject manifest) {
        JSONArray audit = optimization(manifest).optJSONArray("loader_audit");
        if (audit == null || audit.length() == 0) return "";
        StringBuilder out = new StringBuilder("### Auditoria dos drivers carregados\n\n"
                + "| Etapa | Rodada | Papel | Loader | Pacote | SHA-256 | Sucesso |\n"
                + "|---|---:|---|---|---|---|---|\n");
        for (int i = 0; i < audit.length(); i++) {
            JSONObject a = audit.optJSONObject(i);
            if (a == null) continue;
            out.append("| ").append(escape(a.optString("step_id", "—"))).append(" | ")
                    .append(a.optInt("round", -1)).append(" | ")
                    .append(escape(a.optString("role", "—"))).append(" | ")
                    .append(escape(a.optString("mode", "—"))).append(" | ")
                    .append(escape(string(a.opt("driver_name")))).append(" | `")
                    .append(shortSha(string(a.opt("driver_sha256")))).append("` | ")
                    .append(a.optBoolean("success", false) ? "sim" : "não").append(" |\n");
        }
        return out.append("\n").toString();
    }

    static String metricsMarkdown(JSONObject manifest) {
        JSONArray values = metrics(manifest);
        if (values.length() == 0) return "### Métricas por etapa\n\n- Sem métricas consolidadas.\n\n";
        StringBuilder out = new StringBuilder("### Métricas por etapa\n\n"
                + "| Etapa | Métrica | Referência | Candidato | Delta candidato | P95 ref/cand | CV ref/cand | Classificação |\n"
                + "|---|---|---:|---:|---:|---:|---:|---|\n");
        for (int i = 0; i < values.length(); i++) {
            JSONObject m = values.optJSONObject(i);
            if (m == null) continue;
            JSONObject ref = m.optJSONObject("reference");
            JSONObject cand = m.optJSONObject("candidate");
            String reference = "correctness".equals(m.optString("kind"))
                    ? number(m.opt("reference_value"), 3)
                    : number(ref == null ? null : ref.opt("median"), 3);
            String candidate = "correctness".equals(m.optString("kind"))
                    ? number(m.opt("candidate_value"), 3)
                    : number(cand == null ? null : cand.opt("median"), 3);
            out.append("| ").append(escape(m.optString("label", m.optString("step_id"))))
                    .append(" | `").append(escape(m.optString("metric", "—"))).append("` ")
                    .append(escape(m.optString("unit", ""))).append(" | ")
                    .append(reference).append(" | ").append(candidate).append(" | ")
                    .append(signed(m.opt("candidate_improvement_percent"))).append(" | ")
                    .append(number(ref == null ? null : ref.opt("p95"), 3)).append(" / ")
                    .append(number(cand == null ? null : cand.opt("p95"), 3)).append(" | ")
                    .append(number(ref == null ? null : ref.opt("coefficient_of_variation_percent"), 2))
                    .append("% / ")
                    .append(number(cand == null ? null : cand.opt("coefficient_of_variation_percent"), 2))
                    .append("% | ").append(escape(m.optString("classification", "—")))
                    .append(" |\n");
        }
        return out.append("\n").toString();
    }

    static String findingsMarkdown(JSONObject manifest) {
        JSONObject opt = optimization(manifest);
        JSONObject best = opt.optJSONObject("best_area");
        JSONObject worst = opt.optJSONObject("worst_area");
        StringBuilder out = new StringBuilder("### O que melhorou e o que piorou\n\n");
        if (best == null && worst == null) return out.append("- Resultado inconclusivo.\n\n").toString();
        if (best != null) out.append("- **Melhor resultado:** ")
                .append(escape(best.optString("label", best.optString("step_id"))))
                .append(" — ").append(signed(best.opt("candidate_improvement_percent")))
                .append(" (`").append(escape(best.optString("classification", "—"))).append("`).\n");
        if (worst != null) out.append("- **Pior resultado:** ")
                .append(escape(worst.optString("label", worst.optString("step_id"))))
                .append(" — ").append(signed(worst.opt("candidate_improvement_percent")))
                .append(" (`").append(escape(worst.optString("classification", "—"))).append("`).\n");
        return out.append("\n").toString();
    }

    private static JSONObject target(JSONObject manifest) {
        return optimization(manifest).optJSONObject("hardware_target") == null
                ? new JSONObject() : optimization(manifest).optJSONObject("hardware_target");
    }

    private static JSONArray metrics(JSONObject manifest) {
        JSONArray values = optimization(manifest).optJSONArray("metrics");
        return values == null ? new JSONArray() : values;
    }

    private static JSONObject optimization(JSONObject manifest) {
        JSONObject report = manifest == null ? null : manifest.optJSONObject("report");
        JSONObject value = report == null ? null : report.optJSONObject("optimization_report");
        return value == null ? new JSONObject() : value;
    }

    private static String classify(double delta) {
        if (!Double.isFinite(delta)) return "inconclusive";
        if (delta > PRACTICAL_MARGIN_PERCENT) return "candidate_better";
        if (delta < -PRACTICAL_MARGIN_PERCENT) return "candidate_worse";
        return "technical_tie";
    }

    private static String metricUnit(String metric) {
        if (metric == null) return "";
        String lower = metric.toLowerCase(Locale.US);
        if (lower.contains("gib_s") || lower.contains("throughput")) return "GiB/s";
        if (lower.contains("fps")) return "FPS";
        if (lower.contains("ms") || lower.contains("time")) return "ms";
        if (lower.contains("percent")) return "%";
        return "unidade nativa";
    }

    private static Object finite(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }

    private static String row(String key, String value) {
        return "| " + escape(key) + " | " + escape(value) + " |\n";
    }

    private static String escape(String value) {
        return value == null ? "—" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String string(Object value) {
        return value == null || value == JSONObject.NULL ? "—" : String.valueOf(value);
    }

    private static String shortSha(String value) {
        if (value == null || value.equals("—")) return "—";
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private static String number(Object value, int decimals) {
        if (!(value instanceof Number)) return "—";
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number)
                ? String.format(Locale.US, "% ." + decimals + "f", number).trim() : "—";
    }

    private static String signed(Object value) {
        if (!(value instanceof Number)) return "—";
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? String.format(Locale.US, "%+.2f%%", number) : "—";
    }

    private static String normalize(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9._+-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }
}
