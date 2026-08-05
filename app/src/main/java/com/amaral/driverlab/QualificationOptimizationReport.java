package com.amaral.driverlab;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Builds the compact, issue-ready evidence needed to optimize Turnip packages. */
final class QualificationOptimizationReport {
    static final int FORMAT_VERSION = 2;
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
        JSONObject comparisonSummary = comparisonSummary(metrics);
        return new JSONObject()
                .put("format_version", FORMAT_VERSION)
                .put("hardware_target", target)
                .put("loader_audit", loaderAudit)
                .put("metrics", metrics)
                .put("comparison_summary", comparisonSummary)
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
                    .put("lower_is_better", false)
                    .put("reference_value", 100.0)
                    .put("candidate_value", finite(match))
                    .put("absolute_difference", finite(match - 100.0))
                    .put("candidate_to_reference_ratio", finite(match / 100.0))
                    .put("maximum_divergent_blocks",
                            render.opt("maximum_divergent_block_count"))
                    .put("comparison_count", render.optInt("comparison_count", 0))
                    .put("classification", passed ? "passed" : "failed")
                    .put("winner", passed ? "tie" : DriverExecutionIdentity.ROLE_REFERENCE)
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
        JSONObject reference = compactStats(referenceStats);
        JSONObject candidate = compactStats(candidateStats);
        double referenceMedian = numeric(reference.opt("median"));
        double candidateMedian = numeric(candidate.opt("median"));
        double referenceMean = numeric(reference.opt("mean"));
        double candidateMean = numeric(candidate.opt("mean"));
        double referenceP95 = numeric(reference.opt("p95"));
        double candidateP95 = numeric(candidate.opt("p95"));
        double referenceP99 = numeric(reference.opt("p99"));
        double candidateP99 = numeric(candidate.opt("p99"));
        double referenceCv = numeric(reference.opt("coefficient_of_variation_percent"));
        double candidateCv = numeric(candidate.opt("coefficient_of_variation_percent"));
        return item.put("kind", "performance")
                .put("metric", metricName)
                .put("unit", metricUnit(metricName))
                .put("lower_is_better", lowerIsBetter)
                .put("reference", reference)
                .put("candidate", candidate)
                .put("absolute_difference", finite(candidateMedian - referenceMedian))
                .put("mean_difference", finite(candidateMean - referenceMean))
                .put("p95_difference", finite(candidateP95 - referenceP95))
                .put("p99_difference", finite(candidateP99 - referenceP99))
                .put("coefficient_of_variation_difference_pp",
                        finite(candidateCv - referenceCv))
                .put("candidate_to_reference_ratio",
                        finite(ratio(candidateMedian, referenceMedian)))
                .put("candidate_improvement_percent", finite(delta))
                .put("classification", classification)
                .put("winner", winner(classification))
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
        JSONArray values = metrics(manifest);
        if (values.length() == 0) return "Sem métricas consolidadas.";
        StringBuilder out = new StringBuilder();
        JSONObject summary = comparisonSummary(manifest);
        out.append("Placar por etapas: CANDIDATO ")
                .append(summary.optInt("candidate_stage_wins", 0))
                .append(" × ").append(summary.optInt("reference_stage_wins", 0))
                .append(" REFERÊNCIA")
                .append(" · empates ").append(summary.optInt("technical_ties", 0))
                .append(" · inconclusivas ").append(summary.optInt("inconclusive_stages", 0));
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            out.append("\n\n").append(item.optString("label", item.optString("step_id")))
                    .append("\nVencedor: ").append(winnerLabel(item.optString("winner", "inconclusive")));
            if ("correctness".equals(item.optString("kind"))) {
                out.append("\nREFERÊNCIA: ").append(number(item.opt("reference_value"), 4)).append("%")
                        .append("\nCANDIDATO: ").append(number(item.opt("candidate_value"), 4)).append("%")
                        .append("\nDiferença: ").append(number(item.opt("absolute_difference"), 4))
                        .append(" p.p.")
                        .append("\nBlocos divergentes: ")
                        .append(string(item.opt("maximum_divergent_blocks")))
                        .append(" · comparações: ").append(item.optInt("comparison_count", 0));
            } else {
                JSONObject ref = item.optJSONObject("reference");
                JSONObject cand = item.optJSONObject("candidate");
                String unit = item.optString("unit", "");
                out.append("\nREFERÊNCIA — mediana ").append(number(ref == null ? null : ref.opt("median"), 3))
                        .append(", média ").append(number(ref == null ? null : ref.opt("mean"), 3))
                        .append(", P95 ").append(number(ref == null ? null : ref.opt("p95"), 3))
                        .append(", P99 ").append(number(ref == null ? null : ref.opt("p99"), 3))
                        .append(" ").append(unit)
                        .append("\nCANDIDATO — mediana ").append(number(cand == null ? null : cand.opt("median"), 3))
                        .append(", média ").append(number(cand == null ? null : cand.opt("mean"), 3))
                        .append(", P95 ").append(number(cand == null ? null : cand.opt("p95"), 3))
                        .append(", P99 ").append(number(cand == null ? null : cand.opt("p99"), 3))
                        .append(" ").append(unit)
                        .append("\nDiferença absoluta: ").append(number(item.opt("absolute_difference"), 3))
                        .append(" ").append(unit)
                        .append(" · delta normalizado: ")
                        .append(signed(item.opt("candidate_improvement_percent")))
                        .append(" · razão cand/ref: ")
                        .append(number(item.opt("candidate_to_reference_ratio"), 4))
                        .append("\nCV ref/cand: ")
                        .append(number(ref == null ? null : ref.opt("coefficient_of_variation_percent"), 2))
                        .append("% / ")
                        .append(number(cand == null ? null : cand.opt("coefficient_of_variation_percent"), 2))
                        .append("% · amostras ref/cand: ")
                        .append(ref == null ? 0 : ref.optInt("sample_count", 0))
                        .append(" / ").append(cand == null ? 0 : cand.optInt("sample_count", 0))
                        .append(" · pares: ").append(item.optInt("paired_sample_count", 0));
            }
        }
        return out.toString();
    }

    static String driverIdentityMarkdown(JSONObject manifest) {
        JSONObject candidate = manifest == null ? null : manifest.optJSONObject("driver");
        JSONObject reference = manifest == null ? null : manifest.optJSONObject("reference_driver");
        boolean systemReference = !"turnip_vs_turnip".equals(
                manifest == null ? "" : manifest.optString("comparison_mode", ""));
        String referencePackage = systemReference
                ? "Driver do sistema Android" : driverPackageLabel(reference);
        String referenceSha = systemReference ? "system" : driverSha(reference);
        return "### Identidade dos drivers — não confundir os papéis\n\n"
                + "> **DRIVER CANDIDATO:** versão nova ou experimental que está sendo avaliada.  \n"
                + "> **DRIVER DE REFERÊNCIA:** baseline usado para medir se o candidato melhorou ou piorou.\n\n"
                + "| Papel no teste | Pacote carregado | Loader | SHA-256 completo |\n"
                + "|---|---|---|---|\n"
                + "| **DRIVER CANDIDATO** | " + escape(driverPackageLabel(candidate))
                + " | `custom / candidate` | `" + escape(driverSha(candidate)) + "` |\n"
                + "| **DRIVER DE REFERÊNCIA** | " + escape(referencePackage)
                + " | `" + (systemReference ? "system / system" : "custom / reference")
                + "` | `" + escape(referenceSha) + "` |\n\n";
    }

    static String comparisonSummaryMarkdown(JSONObject manifest) {
        JSONObject summary = comparisonSummary(manifest);
        if (summary.optInt("performance_stage_count", 0) == 0) return "";
        return "### Placar geral da comparação\n\n"
                + "| Indicador | Resultado |\n|---|---:|\n"
                + row("Etapas de performance", String.valueOf(summary.optInt("performance_stage_count", 0)))
                + row("Vitórias do DRIVER CANDIDATO",
                        String.valueOf(summary.optInt("candidate_stage_wins", 0)))
                + row("Vitórias do DRIVER DE REFERÊNCIA",
                        String.valueOf(summary.optInt("reference_stage_wins", 0)))
                + row("Empates técnicos", String.valueOf(summary.optInt("technical_ties", 0)))
                + row("Etapas inconclusivas",
                        String.valueOf(summary.optInt("inconclusive_stages", 0)))
                + row("Delta médio do candidato",
                        signed(summary.opt("mean_candidate_improvement_percent")))
                + row("Delta mediano do candidato",
                        signed(summary.opt("median_candidate_improvement_percent")))
                + row("Pares estatísticos válidos",
                        String.valueOf(summary.optInt("total_paired_samples", 0)))
                + row("Rodadas candidato / empate / referência",
                        summary.optInt("candidate_round_wins", 0) + " / "
                                + summary.optInt("tied_rounds", 0) + " / "
                                + summary.optInt("reference_round_wins", 0))
                + row("Vencedor por etapas",
                        winnerLabel(summary.optString("stage_winner", "inconclusive")))
                + "\n";
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
        if (values.length() == 0) {
            return "### Comparativo principal por etapa\n\n- Sem métricas consolidadas.\n\n";
        }
        StringBuilder out = new StringBuilder("### Comparativo principal por etapa\n\n"
                + "| Etapa | Melhor valor | DRIVER DE REFERÊNCIA (mediana) | DRIVER CANDIDATO (mediana) | Diferença absoluta | Delta normalizado | Vencedor | IC 95% |\n"
                + "|---|---|---:|---:|---:|---:|---|---|\n");
        for (int i = 0; i < values.length(); i++) {
            JSONObject m = values.optJSONObject(i);
            if (m == null) continue;
            JSONObject ref = m.optJSONObject("reference");
            JSONObject cand = m.optJSONObject("candidate");
            boolean correctness = "correctness".equals(m.optString("kind"));
            String reference = correctness
                    ? number(m.opt("reference_value"), 4)
                    : number(ref == null ? null : ref.opt("median"), 3);
            String candidate = correctness
                    ? number(m.opt("candidate_value"), 4)
                    : number(cand == null ? null : cand.opt("median"), 3);
            String unit = m.optString("unit", "");
            String absolute = number(m.opt("absolute_difference"), correctness ? 4 : 3)
                    + (correctness ? " p.p." : (unit.isEmpty() ? "" : " " + escape(unit)));
            out.append("| ").append(escape(m.optString("label", m.optString("step_id"))))
                    .append(" | ").append(correctness ? "mais próximo de 100%" :
                            (m.optBoolean("lower_is_better", false) ? "menor" : "maior"))
                    .append(" | ").append(reference).append(" ").append(escape(unit))
                    .append(" | ").append(candidate).append(" ").append(escape(unit))
                    .append(" | ").append(absolute)
                    .append(" | ").append(signed(m.opt("candidate_improvement_percent")))
                    .append(" | **").append(winnerLabel(m.optString("winner", "inconclusive")))
                    .append("** | ").append(interval(m.optJSONObject("confidence_interval_95_percent")))
                    .append(" |\n");
        }
        return out.append("\n").toString();
    }

    static String detailedMetricsMarkdown(JSONObject manifest) {
        JSONArray values = metrics(manifest);
        if (values.length() == 0) return "";
        StringBuilder out = new StringBuilder("### Estatística detalhada por etapa\n\n");
        for (int i = 0; i < values.length(); i++) {
            JSONObject m = values.optJSONObject(i);
            if (m == null) continue;
            String label = m.optString("label", m.optString("step_id", "Etapa"));
            out.append("<details><summary><strong>")
                    .append(escape(label)).append("</strong> — ")
                    .append(winnerLabel(m.optString("winner", "inconclusive")))
                    .append("</summary>\n\n");
            if ("correctness".equals(m.optString("kind"))) {
                out.append("| Valor | DRIVER DE REFERÊNCIA | DRIVER CANDIDATO | Diferença |\n")
                        .append("|---|---:|---:|---:|\n")
                        .append("| Pixel match | ")
                        .append(number(m.opt("reference_value"), 6)).append("% | ")
                        .append(number(m.opt("candidate_value"), 6)).append("% | ")
                        .append(number(m.opt("absolute_difference"), 6)).append(" p.p. |\n\n")
                        .append("- Blocos divergentes máximos: **")
                        .append(string(m.opt("maximum_divergent_blocks"))).append("**\n")
                        .append("- Comparações visuais: **").append(m.optInt("comparison_count", 0))
                        .append("**\n")
                        .append("- Classificação: `").append(escape(m.optString("classification", "—")))
                        .append("`\n\n");
            } else {
                JSONObject ref = m.optJSONObject("reference");
                JSONObject cand = m.optJSONObject("candidate");
                String unit = m.optString("unit", "");
                out.append("| Estatística | DRIVER DE REFERÊNCIA | DRIVER CANDIDATO | Diferença cand-ref |\n")
                        .append("|---|---:|---:|---:|\n")
                        .append(detailRow("Mediana", ref, cand, "median", unit,
                                m.opt("absolute_difference"), false))
                        .append(detailRow("Média", ref, cand, "mean", unit,
                                m.opt("mean_difference"), false))
                        .append(detailRow("P95", ref, cand, "p95", unit,
                                m.opt("p95_difference"), false))
                        .append(detailRow("P99", ref, cand, "p99", unit,
                                m.opt("p99_difference"), false))
                        .append(detailRow("Coeficiente de variação", ref, cand,
                                "coefficient_of_variation_percent", "%",
                                m.opt("coefficient_of_variation_difference_pp"), true))
                        .append(detailRow("Amostras", ref, cand, "sample_count", "",
                                JSONObject.NULL, false))
                        .append("\n- Razão candidato/referência: **")
                        .append(number(m.opt("candidate_to_reference_ratio"), 5)).append("×**\n")
                        .append("- Melhoria normalizada do candidato: **")
                        .append(signed(m.opt("candidate_improvement_percent"))).append("**\n")
                        .append("- Pares estatísticos válidos: **")
                        .append(m.optInt("paired_sample_count", 0)).append("**\n")
                        .append("- Rodadas vencidas — candidato / empate / referência: **")
                        .append(m.optInt("wins", 0)).append(" / ")
                        .append(m.optInt("ties", 0)).append(" / ")
                        .append(m.optInt("losses", 0)).append("**\n")
                        .append("- Intervalo de confiança de 95%: **")
                        .append(interval(m.optJSONObject("confidence_interval_95_percent")))
                        .append("**\n")
                        .append("- Classificação: `")
                        .append(escape(m.optString("classification", "—"))).append("`\n\n");
            }
            out.append("</details>\n\n");
        }
        return out.toString();
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

    private static JSONObject comparisonSummary(JSONArray metrics) throws Exception {
        int performanceStages = 0;
        int candidateStageWins = 0;
        int referenceStageWins = 0;
        int technicalTies = 0;
        int inconclusiveStages = 0;
        int pairedSamples = 0;
        int candidateRoundWins = 0;
        int tiedRounds = 0;
        int referenceRoundWins = 0;
        JSONArray deltas = new JSONArray();
        for (int i = 0; i < metrics.length(); i++) {
            JSONObject metric = metrics.optJSONObject(i);
            if (metric == null || !"performance".equals(metric.optString("kind"))) continue;
            performanceStages++;
            String stageWinner = metric.optString("winner", "inconclusive");
            if (DriverExecutionIdentity.ROLE_CANDIDATE.equals(stageWinner)) candidateStageWins++;
            else if (DriverExecutionIdentity.ROLE_REFERENCE.equals(stageWinner)) referenceStageWins++;
            else if ("tie".equals(stageWinner)) technicalTies++;
            else inconclusiveStages++;
            double delta = metric.optDouble("candidate_improvement_percent", Double.NaN);
            if (Double.isFinite(delta)) deltas.put(delta);
            pairedSamples += metric.optInt("paired_sample_count", 0);
            candidateRoundWins += metric.optInt("wins", 0);
            tiedRounds += metric.optInt("ties", 0);
            referenceRoundWins += metric.optInt("losses", 0);
        }
        return new JSONObject()
                .put("performance_stage_count", performanceStages)
                .put("candidate_stage_wins", candidateStageWins)
                .put("reference_stage_wins", referenceStageWins)
                .put("technical_ties", technicalTies)
                .put("inconclusive_stages", inconclusiveStages)
                .put("mean_candidate_improvement_percent", finite(mean(deltas)))
                .put("median_candidate_improvement_percent", finite(median(deltas)))
                .put("total_paired_samples", pairedSamples)
                .put("candidate_round_wins", candidateRoundWins)
                .put("tied_rounds", tiedRounds)
                .put("reference_round_wins", referenceRoundWins)
                .put("stage_winner", stageWinner(candidateStageWins, referenceStageWins,
                        technicalTies, inconclusiveStages));
    }

    private static JSONObject comparisonSummary(JSONObject manifest) {
        JSONObject value = optimization(manifest).optJSONObject("comparison_summary");
        if (value != null) return value;
        try { return comparisonSummary(metrics(manifest)); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static double mean(JSONArray values) {
        if (values.length() == 0) return Double.NaN;
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < values.length(); i++) {
            double value = values.optDouble(i, Double.NaN);
            if (!Double.isFinite(value)) continue;
            sum += value;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double median(JSONArray values) {
        int count = values.length();
        if (count == 0) return Double.NaN;
        double[] sorted = new double[count];
        int valid = 0;
        for (int i = 0; i < count; i++) {
            double value = values.optDouble(i, Double.NaN);
            if (Double.isFinite(value)) sorted[valid++] = value;
        }
        if (valid == 0) return Double.NaN;
        java.util.Arrays.sort(sorted, 0, valid);
        int middle = valid / 2;
        return valid % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private static String winner(String classification) {
        if ("candidate_better".equals(classification)) {
            return DriverExecutionIdentity.ROLE_CANDIDATE;
        }
        if ("candidate_worse".equals(classification)) {
            return DriverExecutionIdentity.ROLE_REFERENCE;
        }
        if ("technical_tie".equals(classification)
                || "practically_equivalent".equals(classification)) return "tie";
        return "inconclusive";
    }

    private static String stageWinner(int candidateWins, int referenceWins,
                                      int ties, int inconclusive) {
        if (candidateWins > referenceWins) return DriverExecutionIdentity.ROLE_CANDIDATE;
        if (referenceWins > candidateWins) return DriverExecutionIdentity.ROLE_REFERENCE;
        if (candidateWins == referenceWins && candidateWins > 0) return "tie";
        if (ties > 0 && inconclusive == 0) return "tie";
        return "inconclusive";
    }

    private static String winnerLabel(String winner) {
        if (DriverExecutionIdentity.ROLE_CANDIDATE.equals(winner)) return "DRIVER CANDIDATO";
        if (DriverExecutionIdentity.ROLE_REFERENCE.equals(winner)) return "DRIVER DE REFERÊNCIA";
        if ("tie".equals(winner)) return "EMPATE TÉCNICO";
        return "INCONCLUSIVO";
    }

    private static String driverPackageLabel(JSONObject driver) {
        if (driver == null) return "—";
        String name = driver.optString("name", "Turnip");
        String version = driver.optString("packageVersion",
                driver.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }

    private static String driverSha(JSONObject driver) {
        return driver == null ? "—" : driver.optString("sha256", "—");
    }

    private static double numeric(Object value) {
        if (!(value instanceof Number)) return Double.NaN;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : Double.NaN;
    }

    private static double ratio(double candidate, double reference) {
        return Double.isFinite(candidate) && Double.isFinite(reference) && reference != 0.0
                ? candidate / reference : Double.NaN;
    }

    private static String interval(JSONObject value) {
        if (value == null) return "—";
        return "[" + signed(value.opt("lower")) + "; " + signed(value.opt("upper")) + "]";
    }

    private static String detailRow(String label, JSONObject reference, JSONObject candidate,
                                    String key, String unit, Object difference,
                                    boolean differenceIsPercentagePoints) {
        String ref = number(reference == null ? null : reference.opt(key),
                "sample_count".equals(key) ? 0 : 3);
        String cand = number(candidate == null ? null : candidate.opt(key),
                "sample_count".equals(key) ? 0 : 3);
        String suffix = unit.isEmpty() ? "" : " " + unit;
        String diff;
        if (!(difference instanceof Number)) diff = "—";
        else diff = number(difference, 3)
                + (differenceIsPercentagePoints ? " p.p." : suffix);
        return "| " + escape(label) + " | " + ref + suffix + " | "
                + cand + suffix + " | " + diff + " |\n";
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
