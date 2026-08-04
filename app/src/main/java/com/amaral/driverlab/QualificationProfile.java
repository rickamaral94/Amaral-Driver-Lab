package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class QualificationProfile {
    static final class Step {
        final String stepId;
        final String label;
        final String workloadId;
        final String traceId;
        final int rounds;
        final int warmupSeconds;
        final int measureSeconds;
        final int cooldownSeconds;
        final int weight;
        final boolean compatibilityGate;

        Step(String stepId, String label, String workloadId, String traceId,
             int rounds, int warmupSeconds, int measureSeconds, int cooldownSeconds,
             int weight, boolean compatibilityGate) {
            this.stepId = stepId;
            this.label = label;
            this.workloadId = workloadId;
            this.traceId = traceId == null ? "" : traceId;
            this.rounds = rounds;
            this.warmupSeconds = warmupSeconds;
            this.measureSeconds = measureSeconds;
            this.cooldownSeconds = cooldownSeconds;
            this.weight = weight;
            this.compatibilityGate = compatibilityGate;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("step_id", stepId)
                    .put("label", label)
                    .put("workload_id", workloadId)
                    .put("workload_version", WorkloadContract.versionFor(workloadId))
                    .put("trace_id", traceId.isEmpty() ? JSONObject.NULL : traceId)
                    .put("trace_version", traceId.isEmpty() ? JSONObject.NULL
                            : TraceReplayContract.definition(traceId).optInt("trace_version", 1))
                    .put("rounds", rounds)
                    .put("warmup_seconds", warmupSeconds)
                    .put("measure_seconds", measureSeconds)
                    .put("cooldown_seconds", cooldownSeconds)
                    .put("score_weight", weight)
                    .put("compatibility_gate", compatibilityGate);
        }
    }

    private QualificationProfile() {}

    static int currentVersion() {
        return Phase8Contract.CURRENT_FULL_PROFILE_VERSION;
    }

    static List<Step> steps() {
        return stepsForVersion(currentVersion());
    }

    static List<Step> stepsForVersion(int version) {
        if (version == Phase7Contract.PROFILE_VERSION) return legacyV1Steps();
        if (version == Phase8Contract.CURRENT_FULL_PROFILE_VERSION) return v2Steps();
        throw new IllegalArgumentException("Versão Full Qualification desconhecida: " + version);
    }

    private static List<Step> legacyV1Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção visual inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 8, 0, true));
        output.add(new Step("shader_compile", "Compilação de shaders",
                WorkloadContract.SHADER_COMPILE_ID, "", 5, 1, 1, 10, 10, false));
        output.add(new Step("renderpass_tiling", "Render pass, tiling e GMEM",
                WorkloadContract.RENDERPASS_TILING_ID, "", 5, 3, 10, 12, 20, false));
        output.add(new Step("compute_arithmetic", "Compute aritmético",
                WorkloadContract.COMPUTE_ARITHMETIC_ID, "", 5, 3, 10, 10, 10, false));
        output.add(new Step("transfer", "Transferência fill/copy",
                WorkloadContract.TRANSFER_ID, "", 5, 3, 10, 10, 5, false));
        output.add(new Step("stable_scene", "Frametime da cena estável",
                WorkloadContract.STABLE_SCENE_ID, "", 5, 3, 12, 12, 20, false));
        output.add(new Step("trace_mixed", "Trace gráfico/compute/barreiras",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.MIXED_TRACE_ID,
                5, 3, 10, 12, 15, true));
        output.add(new Step("trace_compute", "Trace de dependências compute",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.COMPUTE_CHAIN_TRACE_ID,
                5, 3, 10, 12, 10, true));
        output.add(new Step("thermal_sustain", "Sustentação térmica",
                WorkloadContract.THERMAL_SUSTAIN_ID, "", 5, 3, 30, 15, 10, false));
        output.add(new Step("correctness_post", "Correção visual após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 0, 0, true));
        return Collections.unmodifiableList(output);
    }

    private static List<Step> v2Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção offscreen inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 8, 0, true));
        output.add(new Step("visual_geometry", "Cena visível: geometria e depth",
                VisualSceneContract.GEOMETRY_ID, "", 5, 2, 10, 12, 20, true));
        output.add(new Step("visual_materials", "Cena visível: materiais procedurais",
                VisualSceneContract.MATERIALS_ID, "", 5, 2, 10, 12, 15, true));
        output.add(new Step("visual_postprocess", "Cena visível: pós-processamento",
                VisualSceneContract.POSTPROCESS_ID, "", 5, 2, 10, 12, 15, true));
        output.add(new Step("shader_compile", "Compilação de shaders",
                WorkloadContract.SHADER_COMPILE_ID, "", 5, 1, 1, 10, 8, false));
        output.add(new Step("renderpass_tiling", "Render pass, tiling e GMEM",
                WorkloadContract.RENDERPASS_TILING_ID, "", 5, 3, 10, 12, 10, false));
        output.add(new Step("compute_arithmetic", "Compute aritmético",
                WorkloadContract.COMPUTE_ARITHMETIC_ID, "", 5, 3, 10, 10, 7, false));
        output.add(new Step("transfer", "Transferência fill/copy",
                WorkloadContract.TRANSFER_ID, "", 5, 3, 10, 10, 3, false));
        output.add(new Step("stable_scene", "Frametime da cena estável",
                WorkloadContract.STABLE_SCENE_ID, "", 5, 3, 12, 12, 10, false));
        output.add(new Step("trace_mixed", "Trace gráfico/compute/barreiras",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.MIXED_TRACE_ID,
                5, 3, 10, 12, 5, true));
        output.add(new Step("trace_compute", "Trace de dependências compute",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.COMPUTE_CHAIN_TRACE_ID,
                5, 3, 10, 12, 4, true));
        output.add(new Step("thermal_sustain", "Sustentação térmica",
                WorkloadContract.THERMAL_SUSTAIN_ID, "", 5, 3, 30, 15, 3, false));
        output.add(new Step("correctness_post", "Correção offscreen após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 0, 0, true));
        return Collections.unmodifiableList(output);
    }

    static JSONObject definition() throws Exception {
        return definitionForVersion(currentVersion());
    }

    static JSONObject definitionForVersion(int version) throws Exception {
        JSONArray encodedSteps = new JSONArray();
        int totalWeight = 0;
        for (Step step : stepsForVersion(version)) {
            encodedSteps.put(step.toJson());
            totalWeight += step.weight;
        }
        String label = version == Phase7Contract.PROFILE_VERSION
                ? Phase7Contract.PROFILE_LABEL : Phase8Contract.FULL_PROFILE_LABEL;
        String limitations = version == Phase7Contract.PROFILE_VERSION
                ? Phase7Contract.LIMITATION : Phase8Contract.LIMITATION;
        JSONObject definition = new JSONObject()
                .put("profile_id", Phase7Contract.PROFILE_ID)
                .put("profile_version", version)
                .put("label", label)
                .put("mode", "ab_system_vs_candidate")
                .put("step_count", encodedSteps.length())
                .put("performance_weight_total", totalWeight)
                .put("default_pixel_tolerance", version >= 2
                        ? VisualSceneContract.DEFAULT_PIXEL_TOLERANCE
                        : WorkloadContract.DEFAULT_PIXEL_TOLERANCE)
                .put("default_maximum_divergent_blocks", version >= 2
                        ? VisualSceneContract.DEFAULT_MAX_DIVERGENT_BLOCKS
                        : WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS)
                .put("steps", encodedSteps)
                .put("limitations", limitations);
        definition.put("profile_sha256", JsonCanonicalizer.sha256WithoutKey(
                definition, "profile_sha256"));
        return definition;
    }

    static boolean verify(JSONObject profile) {
        try {
            if (!Phase7Contract.PROFILE_ID.equals(profile.optString("profile_id"))) return false;
            int version = profile.optInt("profile_version", -1);
            if (version != Phase7Contract.PROFILE_VERSION
                    && version != Phase8Contract.CURRENT_FULL_PROFILE_VERSION) return false;
            JSONArray steps = profile.optJSONArray("steps");
            if (steps == null || steps.length() != stepsForVersion(version).size()) return false;
            String expected = profile.optString("profile_sha256", "");
            if (expected.length() != 64 || !expected.equalsIgnoreCase(
                    JsonCanonicalizer.sha256WithoutKey(profile, "profile_sha256"))) return false;
            JSONObject canonical = definitionForVersion(version);
            return expected.equalsIgnoreCase(canonical.optString("profile_sha256"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static Step step(String stepId) {
        return step(currentVersion(), stepId);
    }

    static Step step(int profileVersion, String stepId) {
        for (Step step : stepsForVersion(profileVersion)) {
            if (step.stepId.equals(stepId)) return step;
        }
        return null;
    }
}
