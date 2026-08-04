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

    static List<Step> steps() {
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

    static JSONObject definition() throws Exception {
        JSONArray encodedSteps = new JSONArray();
        int totalWeight = 0;
        for (Step step : steps()) {
            encodedSteps.put(step.toJson());
            totalWeight += step.weight;
        }
        JSONObject definition = new JSONObject()
                .put("profile_id", Phase7Contract.PROFILE_ID)
                .put("profile_version", Phase7Contract.PROFILE_VERSION)
                .put("label", Phase7Contract.PROFILE_LABEL)
                .put("mode", "ab_system_vs_candidate")
                .put("step_count", encodedSteps.length())
                .put("performance_weight_total", totalWeight)
                .put("default_pixel_tolerance", WorkloadContract.DEFAULT_PIXEL_TOLERANCE)
                .put("default_maximum_divergent_blocks",
                        WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS)
                .put("steps", encodedSteps)
                .put("limitations", Phase7Contract.LIMITATION);
        definition.put("profile_sha256", JsonCanonicalizer.sha256WithoutKey(
                definition, "profile_sha256"));
        return definition;
    }

    static boolean verify(JSONObject profile) {
        try {
            if (!Phase7Contract.PROFILE_ID.equals(profile.optString("profile_id"))) return false;
            if (profile.optInt("profile_version", -1) != Phase7Contract.PROFILE_VERSION) return false;
            JSONArray steps = profile.optJSONArray("steps");
            if (steps == null || steps.length() != steps().size()) return false;
            String expected = profile.optString("profile_sha256", "");
            if (expected.length() != 64 || !expected.equalsIgnoreCase(
                    JsonCanonicalizer.sha256WithoutKey(profile, "profile_sha256"))) return false;
            JSONObject canonical = definition();
            return expected.equalsIgnoreCase(canonical.optString("profile_sha256"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static Step step(String stepId) {
        for (Step step : steps()) if (step.stepId.equals(stepId)) return step;
        return null;
    }
}
