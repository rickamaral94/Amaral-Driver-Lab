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
            JSONObject breakdown = failureBreakdown(loaderAudit, stepId);
            if (breakdown != null) {
                metric.put("failure_breakdown", breakdown);
                applyOneSidedStatus(metric, breakdown);
            }
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
        JSONObject runtimeIdentity = runtimeIdentitySummary(loaderAudit);
        return new JSONObject()
                .put("format_version", FORMAT_VERSION)
                .put("hardware_target", target)
                .put("runtime_driver_identity",
                        runtimeIdentity == null ? JSONObject.NULL : runtimeIdentity)
                .put("capability_diff", capabilityDiffSummary(scoredSteps))
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
            JSONObject nativeResult = phase.optJSONObject("native");
            boolean phaseOk = phase.optBoolean("success", false);
            boolean nativeOk = nativeResult == null || nativeResult.optBoolean("success", false);
            JSONObject row = new JSONObject()
                    .put("step_id", stepId)
                    .put("round", phase.optInt("round", -1))
                    .put("role", phase.optString("driver_role",
                            DriverExecutionIdentity.isCandidateArm(phase)
                                    ? DriverExecutionIdentity.ROLE_CANDIDATE
                                    : DriverExecutionIdentity.ROLE_REFERENCE))
                    .put("mode", phase.optString("driver_mode", "unknown"))
                    .put("driver_name", phase.opt("driver_display_name"))
                    .put("driver_sha256", phase.opt("driver_sha256"))
                    .put("success", phaseOk && nativeOk);
            JSONObject runtime = runtimeIdentity(phase);
            if (runtime != null) row.put("runtime_driver", runtime);
            if (!phaseOk || !nativeOk) row.put("failure", failureDetail(phase, nativeResult));
            output.put(row);
        }
    }

    /**
     * Why a round failed, not just that it failed.
     *
     * <p>The native runners already record the Vulkan stage, the VkResult and the
     * failing call, and {@link RunCoordinator} records the process-level reason for
     * crashes and timeouts. None of it reached the report, which is what forced a
     * failing candidate to be diagnosed by rebuilding the driver instead of by
     * reading the round that failed.
     */
    private static JSONObject failureDetail(JSONObject phase, JSONObject nativeResult)
            throws Exception {
        JSONObject source = nativeResult != null
                && !nativeResult.optBoolean("success", false) ? nativeResult : phase;
        JSONObject detail = new JSONObject()
                .put("type", firstNonEmpty(source.optString("failure_type", ""),
                        phase.optString("failure_type", ""), "unknown_failure"))
                .put("stage", firstNonEmpty(source.optString("failure_stage", ""),
                        phase.optString("failure_stage", ""), "unknown"))
                .put("message", firstNonEmpty(source.optString("error", ""),
                        phase.optString("error", ""), ""));
        if (source.has("vk_result")) {
            int code = source.optInt("vk_result", 0);
            detail.put("vk_result", code).put("vk_result_name", VkResultNames.of(code));
        }
        if (source.has("vulkan_operation")) detail.put("api_call", source.opt("vulkan_operation"));
        if (source.optBoolean("device_lost", false)) detail.put("device_lost", true);
        JSONArray validation = phase.optJSONArray("validation_errors");
        if (validation != null && validation.length() > 0) {
            detail.put("validation_errors", validation);
        }
        return detail;
    }

    /**
     * What the driver reports about itself, per round.
     *
     * <p>The package SHA-256 proves which file the loader opened. Only this proves
     * which driver Vulkan ended up using — the distinction that matters when the
     * loader falls back to the system blob, and the only thing that separates two
     * builds of the same Mesa commit.
     */
    private static JSONObject runtimeIdentity(JSONObject phase) throws Exception {
        JSONObject nativeResult = phase.optJSONObject("native");
        if (nativeResult == null) return null;
        JSONObject capabilities = nativeResult.optJSONObject("capabilities");
        JSONObject source = capabilities != null && capabilities.has("driver_id")
                ? capabilities : nativeResult;
        if (!source.has("driver_id") && !source.has("gpu_name")) return null;
        return new JSONObject()
                .put("gpu_name", source.opt("gpu_name"))
                .put("driver_id", source.opt("driver_id"))
                .put("driver_id_name", source.opt("driver_id_name"))
                .put("driver_name", source.opt("driver_name"))
                .put("driver_info", source.opt("driver_info"))
                .put("driver_version_raw", source.opt("driver_version_raw"))
                .put("api_version", source.opt("api_version"))
                .put("vendor_id", source.opt("vendor_id"))
                .put("device_id", source.opt("device_id"))
                .put("conformance_version", source.opt("conformance_version"));
    }

    /**
     * Collapses the per-round runtime identities into one block per arm, and
     * raises the caveats that invalidate a comparison outright.
     */
    private static JSONObject runtimeIdentitySummary(JSONArray audit) throws Exception {
        JSONObject perRole = new JSONObject();
        for (int index = 0; index < audit.length(); index++) {
            JSONObject row = audit.optJSONObject(index);
            if (row == null) continue;
            JSONObject identity = row.optJSONObject("runtime_driver");
            if (identity == null) continue;
            String role = row.optString("role", "unknown");
            if (!perRole.has(role)) perRole.put(role, identity);
        }
        JSONArray caveats = new JSONArray();
        JSONObject candidate = perRole.optJSONObject(DriverExecutionIdentity.ROLE_CANDIDATE);
        JSONObject reference = perRole.optJSONObject(DriverExecutionIdentity.ROLE_REFERENCE);
        if (candidate != null && reference != null) {
            String candidateInfo = candidate.optString("driver_info", "");
            String referenceInfo = reference.optString("driver_info", "");
            if (!candidateInfo.isEmpty() && candidateInfo.equals(referenceInfo)) {
                caveats.put("identical_runtime_driver_info_between_arms:" + candidateInfo);
            }
        }
        for (String role : new String[] {DriverExecutionIdentity.ROLE_CANDIDATE,
                DriverExecutionIdentity.ROLE_REFERENCE}) {
            JSONObject identity = perRole.optJSONObject(role);
            if (identity == null) continue;
            if (identity.optInt("driver_id", -1) != VkResultNames.DRIVER_ID_MESA_TURNIP) {
                caveats.put("arm_is_not_turnip:" + role + ":"
                        + identity.optString("driver_id_name", "unknown"));
            }
        }
        if (perRole.length() == 0) return null;
        return new JSONObject().put("by_role", perRole).put("caveats", caveats);
    }

    /**
     * Groups the failures of a step by stage, VkResult and arm.
     *
     * <p>Twenty identical failures and twenty different ones are opposite
     * diagnoses, and a flat count cannot tell them apart.
     */
    private static JSONObject failureBreakdown(JSONArray audit, String stepId) throws Exception {
        JSONObject byStage = new JSONObject();
        JSONObject byResult = new JSONObject();
        JSONObject byRole = new JSONObject();
        int failed = 0;
        for (int index = 0; index < audit.length(); index++) {
            JSONObject row = audit.optJSONObject(index);
            if (row == null || !stepId.equals(row.optString("step_id"))) continue;
            JSONObject failure = row.optJSONObject("failure");
            if (failure == null) continue;
            failed++;
            increment(byStage, failure.optString("stage", "unknown"));
            increment(byRole, row.optString("role", "unknown"));
            if (failure.has("vk_result_name")) {
                increment(byResult, failure.optString("vk_result_name"));
            }
        }
        if (failed == 0) return null;
        return new JSONObject()
                .put("failed_round_count", failed)
                .put("by_stage", byStage)
                .put("by_vk_result", byResult)
                .put("by_role", byRole);
    }

    private static void increment(JSONObject counter, String key) throws Exception {
        counter.put(key, counter.optInt(key, 0) + 1);
    }

    /**
     * A step where one arm collapsed and the other measured cleanly is a
     * conclusive result, not missing data.
     *
     * <p>Previously it degraded to {@code insufficient_data}, which discarded the
     * surviving arm's samples along with the failed one's — the reference rounds
     * were paid for in battery and heat and then thrown away by a presentation
     * rule. Here the surviving side keeps its statistics and the step is labelled
     * for the arm that failed.
     */
    private static void applyOneSidedStatus(JSONObject metric, JSONObject breakdown)
            throws Exception {
        JSONObject byRole = breakdown.optJSONObject("by_role");
        if (byRole == null) return;
        int candidateSamples = sampleCount(metric.optJSONObject("candidate"));
        int referenceSamples = sampleCount(metric.optJSONObject("reference"));
        boolean candidateFailed = byRole.optInt(DriverExecutionIdentity.ROLE_CANDIDATE, 0) > 0
                && candidateSamples == 0;
        boolean referenceFailed = byRole.optInt(DriverExecutionIdentity.ROLE_REFERENCE, 0) > 0
                && referenceSamples == 0;
        if (candidateFailed && referenceSamples > 0) {
            metric.put("classification", "candidate_failed")
                    .put("winner", DriverExecutionIdentity.ROLE_REFERENCE)
                    .put("surviving_arm", DriverExecutionIdentity.ROLE_REFERENCE);
        } else if (referenceFailed && candidateSamples > 0) {
            metric.put("classification", "reference_failed")
                    .put("winner", DriverExecutionIdentity.ROLE_CANDIDATE)
                    .put("surviving_arm", DriverExecutionIdentity.ROLE_CANDIDATE);
        } else if (candidateFailed && referenceFailed) {
            metric.put("classification", "both_arms_failed").put("winner", "none");
        }
    }

    private static int sampleCount(JSONObject stats) {
        return stats == null ? 0 : stats.optInt("sample_count", 0);
    }

    /**
     * Lifts the capability diff out of the per-suite artifact and into the report.
     *
     * <p>It was already computed and already stored; it just never reached the
     * document anyone reads. An extension that disappeared between two builds
     * explains a regression better than any timing statistic, because it means the
     * emulator took a different code path rather than the same path more slowly.
     */
    private static Object capabilityDiffSummary(JSONArray scoredSteps) throws Exception {
        for (int index = 0; index < scoredSteps.length(); index++) {
            JSONObject scored = scoredSteps.optJSONObject(index);
            if (scored == null) continue;
            JSONObject suite = scored.optJSONObject("report");
            JSONObject diff = suite == null ? null : suite.optJSONObject("capability_diff");
            if (diff == null) continue;
            return new JSONObject()
                    .put("step_id", scored.optString("step_id", "unknown"))
                    .put("summary", diff.opt("summary"))
                    .put("driver_identity_changed", diff.opt("driver_identity_changed"))
                    .put("extensions_gained", diff.opt("extensions_gained"))
                    .put("extensions_lost", diff.opt("extensions_lost"))
                    .put("features_gained", diff.opt("features_gained"))
                    .put("features_lost", diff.opt("features_lost"))
                    .put("limits_increased", diff.opt("limits_increased"))
                    .put("limits_decreased", diff.opt("limits_decreased"));
        }
        return JSONObject.NULL;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
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
                + "| Etapa | Rodada | Papel | Loader | Pacote | SHA-256 | Sucesso | Falha |\n"
                + "|---|---:|---|---|---|---|---|---|\n");
        for (int i = 0; i < audit.length(); i++) {
            JSONObject a = audit.optJSONObject(i);
            if (a == null) continue;
            out.append("| ").append(escape(a.optString("step_id", "—"))).append(" | ")
                    .append(a.optInt("round", -1)).append(" | ")
                    .append(escape(a.optString("role", "—"))).append(" | ")
                    .append(escape(a.optString("mode", "—"))).append(" | ")
                    .append(escape(string(a.opt("driver_name")))).append(" | `")
                    .append(shortSha(string(a.opt("driver_sha256")))).append("` | ")
                    .append(a.optBoolean("success", false) ? "sim" : "não").append(" | ")
                    .append(failureCell(a.optJSONObject("failure"))).append(" |\n");
        }
        return out.append("\n").toString();
    }

    private static String failureCell(JSONObject failure) {
        if (failure == null) return "—";
        StringBuilder cell = new StringBuilder(escape(failure.optString("stage", "unknown")));
        String result = failure.optString("vk_result_name", "");
        if (!result.isEmpty()) cell.append(" · ").append(escape(result));
        String call = failure.optString("api_call", "");
        if (!call.isEmpty()) cell.append(" · `").append(escape(call)).append('`');
        return cell.toString();
    }

    /**
     * The runtime identity block: what each arm's driver says about itself.
     *
     * <p>Placed before the metrics because a comparison between two arms that
     * resolved to the same driver, or to the system blob, has no contrast to
     * report and the numbers below it mean nothing.
     */
    static String runtimeIdentityMarkdown(JSONObject manifest) {
        JSONObject identity = optimization(manifest).optJSONObject("runtime_driver_identity");
        if (identity == null) return "";
        JSONObject byRole = identity.optJSONObject("by_role");
        if (byRole == null || byRole.length() == 0) return "";
        StringBuilder out = new StringBuilder("### Identidade do driver em runtime\n\n"
                + "| Papel | driverID | driverName | driverInfo | GPU | API |\n"
                + "|---|---|---|---|---|---|\n");
        java.util.Iterator<String> roles = byRole.keys();
        while (roles.hasNext()) {
            String role = roles.next();
            JSONObject value = byRole.optJSONObject(role);
            if (value == null) continue;
            out.append("| ").append(escape(role)).append(" | ")
                    .append(escape(string(value.opt("driver_id")))).append(" · ")
                    .append(escape(string(value.opt("driver_id_name")))).append(" | ")
                    .append(escape(string(value.opt("driver_name")))).append(" | `")
                    .append(escape(string(value.opt("driver_info")))).append("` | ")
                    .append(escape(string(value.opt("gpu_name")))).append(" | ")
                    .append(escape(string(value.opt("api_version")))).append(" |\n");
        }
        JSONArray caveats = identity.optJSONArray("caveats");
        if (caveats != null && caveats.length() > 0) {
            out.append("\n**Ressalvas que invalidam a comparação:**\n\n");
            for (int i = 0; i < caveats.length(); i++) {
                out.append("- `").append(escape(caveats.optString(i))).append("`\n");
            }
        }
        return out.append("\n").toString();
    }

    /** Failure breakdown per step: how the failures distribute, not how many. */
    static String failureBreakdownMarkdown(JSONObject manifest) {
        JSONArray values = metrics(manifest);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            JSONObject breakdown = item.optJSONObject("failure_breakdown");
            if (breakdown == null) continue;
            out.append("- **").append(escape(item.optString("label",
                            item.optString("step_id")))).append("** — ")
                    .append(breakdown.optInt("failed_round_count", 0))
                    .append(" rodada(s) com falha; estágios: ")
                    .append(escape(counterText(breakdown.optJSONObject("by_stage"))));
            JSONObject byResult = breakdown.optJSONObject("by_vk_result");
            if (byResult != null && byResult.length() > 0) {
                out.append("; VkResult: ").append(escape(counterText(byResult)));
            }
            out.append("; por braço: ")
                    .append(escape(counterText(breakdown.optJSONObject("by_role")))).append("\n");
        }
        if (out.length() == 0) return "";
        return "### Falhas por etapa\n\n" + out + "\n";
    }

    private static String counterText(JSONObject counter) {
        if (counter == null || counter.length() == 0) return "—";
        StringBuilder text = new StringBuilder();
        java.util.Iterator<String> keys = counter.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (text.length() > 0) text.append(", ");
            text.append(key).append('×').append(counter.optInt(key, 0));
        }
        return text.toString();
    }

    /** Capability diff, promoted from the suite artifact into the report body. */
    static String capabilityDiffMarkdown(JSONObject manifest) {
        JSONObject diff = optimization(manifest).optJSONObject("capability_diff");
        if (diff == null) return "";
        StringBuilder out = new StringBuilder("### Diferença de capacidades entre os braços\n\n");
        int gained = length(diff.optJSONArray("extensions_gained"));
        int lost = length(diff.optJSONArray("extensions_lost"));
        int featuresGained = length(diff.optJSONArray("features_gained"));
        int featuresLost = length(diff.optJSONArray("features_lost"));
        if (gained + lost + featuresGained + featuresLost == 0) {
            out.append("- Nenhuma diferença de capacidade entre os braços.\n");
        } else {
            out.append("- Extensões: +").append(gained).append(" / −").append(lost)
                    .append(" · features: +").append(featuresGained)
                    .append(" / −").append(featuresLost).append("\n");
            appendList(out, "Extensões perdidas", diff.optJSONArray("extensions_lost"));
            appendList(out, "Extensões ganhas", diff.optJSONArray("extensions_gained"));
            appendList(out, "Features perdidas", diff.optJSONArray("features_lost"));
            appendList(out, "Limites reduzidos", diff.optJSONArray("limits_decreased"));
        }
        return out.append("\n").toString();
    }

    private static void appendList(StringBuilder out, String label, JSONArray values) {
        if (values == null || values.length() == 0) return;
        out.append("- ").append(label).append(": ");
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) out.append(", ");
            out.append('`').append(escape(values.optString(i))).append('`');
        }
        out.append('\n');
    }

    private static int length(JSONArray values) {
        return values == null ? 0 : values.length();
    }

    /**
     * Temperature and charge per step, plus the reason consumption may be
     * unreadable.
     *
     * <p>The project's guidelines make temperature and consumption mandatory in an
     * A/B: a gain paid for in either is a trade, not a gain. The one case that has
     * to be said out loud rather than reported as a number is a charging device —
     * the charge counter climbs during the run, so any consumption delta computed
     * from it is meaningless, and reporting it as if it were valid is worse than
     * reporting nothing.
     */
    static String thermalMarkdown(JSONObject manifest) {
        JSONArray states = manifest.optJSONObject("execution") == null ? null
                : manifest.optJSONObject("execution").optJSONArray("steps");
        if (states == null || states.length() == 0) return "";
        boolean charging = chargingDuringRun(manifest);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < states.length(); i++) {
            JSONObject state = states.optJSONObject(i);
            if (state == null) continue;
            JSONObject start = deviceOf(state.optJSONObject("environment_start"));
            JSONObject end = deviceOf(state.optJSONObject("environment_end"));
            if (start == null || end == null) continue;
            double startTemp = start.optDouble("battery_temperature_c", Double.NaN);
            double endTemp = end.optDouble("battery_temperature_c", Double.NaN);
            long startCharge = start.optLong("battery_charge_counter_uah", 0L);
            long endCharge = end.optLong("battery_charge_counter_uah", 0L);
            int maxThermal = Math.max(start.optInt("thermal_status", 0),
                    end.optInt("thermal_status", 0));
            rows.append("| ").append(escape(state.optString("step_id", "—"))).append(" | ")
                    .append(number(startTemp, 1)).append(" | ")
                    .append(number(endTemp, 1)).append(" | ")
                    .append(Double.isFinite(startTemp) && Double.isFinite(endTemp)
                            ? String.format(Locale.US, "%+.1f", endTemp - startTemp) : "—")
                    .append(" | ").append(maxThermal).append(" | ")
                    .append(charging ? "n/d (carregando)"
                            : String.valueOf(startCharge - endCharge))
                    .append(" |\n");
        }
        if (rows.length() == 0) return "";
        StringBuilder out = new StringBuilder("### Temperatura e consumo por etapa\n\n");
        if (charging) {
            out.append("> O aparelho estava carregando durante a execução. "
                    + "O contador de carga sobe, então o consumo por etapa não é "
                    + "mensurável nesta corrida e aparece como `n/d`. "
                    + "A comparação térmica continua válida enquanto o estado de "
                    + "carga não mudar no meio da suíte.\n\n");
        }
        out.append("| Etapa | T. início (°C) | T. fim (°C) | Δ | thermal_status máx | Carga gasta (µAh) |\n")
                .append("|---|---:|---:|---:|---:|---:|\n").append(rows);
        return out.append("\n").toString();
    }

    private static JSONObject deviceOf(JSONObject snapshot) {
        if (snapshot == null) return null;
        JSONObject device = snapshot.optJSONObject("device");
        return device == null ? snapshot : device;
    }

    /** BatteryManager.BATTERY_STATUS_CHARGING (2) or FULL (5) at either boundary. */
    private static boolean chargingDuringRun(JSONObject manifest) {
        return isCharging(manifest.optJSONObject("preflight"))
                || isCharging(manifest.optJSONObject("final_environment"));
    }

    private static boolean isCharging(JSONObject environment) {
        JSONObject device = deviceOf(environment);
        if (device == null) return false;
        int status = device.optInt("battery_status", -1);
        return status == 2 || status == 5;
    }

    private static String number(double value, int decimals) {
        return Double.isFinite(value)
                ? String.format(Locale.US, "%." + decimals + "f", value) : "—";
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
