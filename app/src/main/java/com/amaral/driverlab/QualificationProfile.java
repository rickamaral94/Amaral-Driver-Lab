package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class QualificationProfile {
    static final String KIND_SUITE = "suite";
    static final String KIND_DEEP_DIAGNOSTICS = "deep_diagnostics";
    static final String KIND_SHORT_SOAK = "short_soak";

    static final class Step {
        final String stepId;
        final String label;
        final String kind;
        final String workloadId;
        final int workloadVersion;
        final String traceId;
        final int rounds;
        final int warmupSeconds;
        final int measureSeconds;
        final int cooldownSeconds;
        final int weight;
        final boolean compatibilityGate;
        final int diagnosticCycles;
        final int memoryMiB;

        Step(String stepId, String label, String workloadId, String traceId,
             int rounds, int warmupSeconds, int measureSeconds, int cooldownSeconds,
             int weight, boolean compatibilityGate) {
            this(stepId, label, KIND_SUITE, workloadId, 1, traceId, rounds, warmupSeconds,
                    measureSeconds, cooldownSeconds, weight, compatibilityGate, 0, 0);
        }

        Step(String stepId, String label, String workloadId, int workloadVersion,
             String traceId, int rounds, int warmupSeconds, int measureSeconds,
             int cooldownSeconds, int weight, boolean compatibilityGate) {
            this(stepId, label, KIND_SUITE, workloadId, workloadVersion, traceId,
                    rounds, warmupSeconds, measureSeconds, cooldownSeconds, weight,
                    compatibilityGate, 0, 0);
        }

        Step(String stepId, String label, String kind, int cooldownSeconds,
             int weight, boolean compatibilityGate, int diagnosticCycles, int memoryMiB) {
            this(stepId, label, kind, "", 0, "", 0, 0, 0, cooldownSeconds, weight,
                    compatibilityGate, diagnosticCycles, memoryMiB);
        }

        private Step(String stepId, String label, String kind, String workloadId,
                     int workloadVersion,
                     String traceId, int rounds, int warmupSeconds, int measureSeconds,
                     int cooldownSeconds, int weight, boolean compatibilityGate,
                     int diagnosticCycles, int memoryMiB) {
            this.stepId = stepId;
            this.label = label;
            this.kind = kind;
            this.workloadId = workloadId == null ? "" : workloadId;
            this.workloadVersion = workloadVersion;
            this.traceId = traceId == null ? "" : traceId;
            this.rounds = rounds;
            this.warmupSeconds = warmupSeconds;
            this.measureSeconds = measureSeconds;
            this.cooldownSeconds = cooldownSeconds;
            this.weight = weight;
            this.compatibilityGate = compatibilityGate;
            this.diagnosticCycles = diagnosticCycles;
            this.memoryMiB = memoryMiB;
        }

        JSONObject toJson() throws Exception {
            JSONObject output = new JSONObject()
                    .put("step_id", stepId)
                    .put("label", label)
                    .put("step_kind", kind)
                    .put("score_weight", weight)
                    .put("compatibility_gate", compatibilityGate)
                    .put("cooldown_seconds", cooldownSeconds);
            if (KIND_SUITE.equals(kind)) {
                output.put("workload_id", workloadId)
                        .put("workload_version", workloadVersion)
                        .put("trace_id", traceId.isEmpty() ? JSONObject.NULL : traceId)
                        .put("trace_version", traceId.isEmpty() ? JSONObject.NULL
                                : TraceReplayContract.definition(traceId, workloadVersion)
                                .optInt("trace_version", 1))
                        .put("rounds", rounds)
                        .put("warmup_seconds", warmupSeconds)
                        .put("measure_seconds", measureSeconds);
            } else {
                output.put("workload_id", JSONObject.NULL)
                        .put("workload_version", JSONObject.NULL)
                        .put("trace_id", JSONObject.NULL)
                        .put("trace_version", JSONObject.NULL)
                        .put("rounds", JSONObject.NULL)
                        .put("warmup_seconds", JSONObject.NULL)
                        .put("measure_seconds", JSONObject.NULL)
                        .put("diagnostic_mode", KIND_SHORT_SOAK.equals(kind) ? "soak" : "full")
                        .put("diagnostic_cycles", diagnosticCycles)
                        .put("memory_mib", memoryMiB);
            }
            return output;
        }
    }

    private QualificationProfile() {}

    static int currentVersion() { return Phase15DynamicRangeContract.PROFILE_VERSION; }
    static List<Step> steps() { return stepsForVersion(currentVersion()); }

    static List<Step> stepsForVersion(int version) {
        if (version == Phase7Contract.PROFILE_VERSION) return legacyV1Steps();
        if (version == Phase8Contract.CURRENT_FULL_PROFILE_VERSION) return v2Steps();
        if (version == Phase11Contract.PROFILE_VERSION) return v3Steps();
        if (version == Phase13ValidationContract.LEGACY_PROFILE_VERSION) {
            return recommendedV4Steps();
        }
        if (version == Phase13ValidationContract.PROFILE_VERSION) return recommendedV5Steps();
        if (version == Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION) {
            return recommendedV6Steps();
        }
        if (version == Phase15DynamicRangeContract.EMULATOR_V7_PROFILE_VERSION) {
            return emulatorV7Steps();
        }
        if (version == Phase15DynamicRangeContract.PROFILE_VERSION) return emulatorV8Steps();
        throw new IllegalArgumentException("Versão de Qualification desconhecida: " + version);
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

    private static List<Step> v3Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção offscreen inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 8, 0, true));
        output.add(new Step("visual_geometry", "Cena visível: geometria e depth",
                VisualSceneContract.GEOMETRY_ID, "", 5, 2, 10, 12, 15, true));
        output.add(new Step("visual_materials", "Cena visível: materiais procedurais",
                VisualSceneContract.MATERIALS_ID, "", 5, 2, 10, 12, 10, true));
        output.add(new Step("visual_postprocess", "Cena visível: pós-processamento",
                VisualSceneContract.POSTPROCESS_ID, "", 5, 2, 10, 12, 10, true));
        output.add(new Step("shader_compile", "Compilação de shaders",
                WorkloadContract.SHADER_COMPILE_ID, "", 5, 1, 1, 10, 6, false));
        output.add(new Step("renderpass_tiling", "Render pass, tiling e GMEM",
                WorkloadContract.RENDERPASS_TILING_ID, "", 5, 3, 10, 12, 10, false));
        output.add(new Step("compute_arithmetic", "Compute aritmético",
                WorkloadContract.COMPUTE_ARITHMETIC_ID, "", 5, 3, 10, 10, 7, false));
        output.add(new Step("transfer", "Transferência fill/copy",
                WorkloadContract.TRANSFER_ID, "", 5, 3, 10, 10, 3, false));
        output.add(new Step("stable_scene", "Frametime da cena estável",
                WorkloadContract.STABLE_SCENE_ID, "", 5, 3, 12, 12, 12, false));
        output.add(new Step("trace_mixed", "Trace gráfico/compute/barreiras",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.MIXED_TRACE_ID,
                5, 3, 10, 12, 6, true));
        output.add(new Step("trace_compute", "Trace de dependências compute",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.COMPUTE_CHAIN_TRACE_ID,
                5, 3, 10, 12, 4, true));
        output.add(new Step("thermal_sustain", "Sustentação térmica",
                WorkloadContract.THERMAL_SUSTAIN_ID, "", 5, 3, 30, 15, 7, false));
        output.add(new Step("correctness_post", "Correção offscreen após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 8, 0, true));
        output.add(new Step("deep_diagnostics", "Diagnóstico profundo Turnip",
                KIND_DEEP_DIAGNOSTICS, 12, 10, true, 1,
                Phase11Contract.RECOMMENDED_MEMORY_MIB));
        output.add(new Step("short_soak", "Soak curto de confiabilidade · 5 ciclos",
                KIND_SHORT_SOAK, 0, 0, true, Phase11Contract.FULL_SOAK_CYCLES,
                Phase11Contract.RECOMMENDED_MEMORY_MIB));
        return Collections.unmodifiableList(output);
    }

    private static List<Step> recommendedV4Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção offscreen inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 2, 0, true));
        output.add(new Step("visual_geometry", "Cena visível: geometria e depth",
                VisualSceneContract.GEOMETRY_ID, "", 5, 1, 5, 2, 20, true));
        output.add(new Step("visual_materials", "Cena visível: materiais e amostragem",
                VisualSceneContract.MATERIALS_ID, "", 5, 1, 5, 2, 15, true));
        output.add(new Step("visual_postprocess", "Cena visível: pós-processamento",
                VisualSceneContract.POSTPROCESS_ID, "", 5, 1, 5, 2, 15, true));
        output.add(new Step("shader_compile", "Compilação de shaders",
                WorkloadContract.SHADER_COMPILE_ID, "", 5, 1, 1, 2, 15, false));
        output.add(new Step("stable_scene", "Frametime da cena estável",
                WorkloadContract.STABLE_SCENE_ID, "", 5, 2, 6, 2, 25, false));
        output.add(new Step("trace_mixed", "Trace gráfico, compute e barreiras",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.MIXED_TRACE_ID,
                5, 2, 5, 2, 10, true));
        output.add(new Step("correctness_post", "Correção offscreen após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 0, 0, true));
        return Collections.unmodifiableList(output);
    }

    private static List<Step> recommendedV5Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção offscreen inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 2, 0, true));
        output.add(new Step("visual_geometry", "Cena visível: geometria e depth",
                VisualSceneContract.GEOMETRY_ID, "", 5, 1, 5, 2, 15, true));
        output.add(new Step("visual_materials", "Cena visível: materiais e amostragem",
                VisualSceneContract.MATERIALS_ID, "", 5, 1, 5, 2, 10, true));
        output.add(new Step("visual_postprocess", "Cena visível: pós-processamento",
                VisualSceneContract.POSTPROCESS_ID, "", 5, 1, 5, 2, 10, true));
        output.add(new Step("visual_gpu_stress", "Cena avançada: GPU Stress 3D 720p",
                VisualSceneContract.GPU_STRESS_ID, "", 5, 1, 5, 2, 20, true));
        output.add(new Step("shader_compile", "Compilação de shaders",
                WorkloadContract.SHADER_COMPILE_ID, "", 5, 1, 1, 2, 10, false));
        output.add(new Step("stable_scene", "Frametime da cena estável",
                WorkloadContract.STABLE_SCENE_ID, "", 5, 2, 6, 2, 25, false));
        output.add(new Step("trace_mixed", "Trace gráfico, compute e barreiras",
                WorkloadContract.TRACE_REPLAY_ID, TraceReplayContract.MIXED_TRACE_ID,
                5, 2, 5, 2, 10, true));
        output.add(new Step("correctness_post", "Correção offscreen após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, "", 3, 0, 1, 0, 0, true));
        return Collections.unmodifiableList(output);
    }

    private static List<Step> recommendedV6Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção offscreen inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, 1, "", 3, 0, 1, 2, 0, true));
        output.add(new Step("visual_geometry", "Cena visível: geometria e depth",
                VisualSceneContract.GEOMETRY_ID, 2, "", 5, 1, 5, 2, 15, true));
        output.add(new Step("visual_materials", "Cena visível: materiais e amostragem",
                VisualSceneContract.MATERIALS_ID, 2, "", 5, 1, 5, 2, 10, true));
        output.add(new Step("visual_postprocess", "Cena visível: pós-processamento",
                VisualSceneContract.POSTPROCESS_ID, 2, "", 5, 1, 5, 2, 10, true));
        output.add(new Step("visual_gpu_stress", "Cena avançada: GPU Stress 3D 720p",
                VisualSceneContract.GPU_STRESS_ID, 2, "", 5, 1, 5, 2, 20, true));
        output.add(new Step("shader_compile", "Compilação cold de shaders",
                WorkloadContract.SHADER_COMPILE_ID, 2, "", 5, 1, 1, 2, 10, false));
        output.add(new Step("stable_scene", "Frametime da cena estável",
                WorkloadContract.STABLE_SCENE_ID, 2, "", 5, 2, 6, 2, 25, false));
        output.add(new Step("trace_mixed", "Trace gráfico, compute e barreiras",
                WorkloadContract.TRACE_REPLAY_ID, 2, TraceReplayContract.MIXED_TRACE_ID,
                5, 2, 5, 2, 10, true));
        output.add(new Step("correctness_post", "Correção offscreen após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, 1, "", 3, 0, 1, 0, 0, true));
        return Collections.unmodifiableList(output);
    }

    /**
     * The measurement profile rebuilt around what an emulator actually emits.
     *
     * Every A/B run on v6 came back a technical tie at about a tenth of a percent.
     * That was not bad luck: its performance steps render one large, uniform scene
     * with a coefficient of variation near 0.03%, which is a very precise
     * instrument pointed at something that does not vary between drivers.
     *
     * So the performance weight moves to {@code emulator_frame_pattern}, which
     * emits many small render passes with few draws each and sweeps store against
     * discard, and to cold shader compilation — the one axis that ever showed a
     * large separation (44.7% between Turnip and the blob, issue #30).
     *
     * The visible scenes and the offscreen correctness stay, with weight zero.
     * They are not weak; they are the gates that caught driver v8b failing 92 of
     * 92 presentation rounds. Removing them to make room would have traded the
     * only part of the suite that works for the part that did not.
     */
    private static List<Step> emulatorV7Steps() {
        List<Step> output = new ArrayList<>();
        output.add(new Step("correctness_pre", "Correção offscreen inicial",
                WorkloadContract.RENDER_CORRECTNESS_ID, 1, "", 3, 0, 1, 2, 0, true));
        output.add(new Step("visual_geometry", "Gate visível: geometria e depth",
                VisualSceneContract.GEOMETRY_ID, 2, "", 3, 1, 3, 2, 0, true));
        output.add(new Step("visual_materials", "Gate visível: materiais e amostragem",
                VisualSceneContract.MATERIALS_ID, 2, "", 3, 1, 3, 2, 0, true));
        output.add(new Step("visual_postprocess", "Gate visível: pós-processamento",
                VisualSceneContract.POSTPROCESS_ID, 2, "", 3, 1, 3, 2, 0, true));
        output.add(new Step("visual_gpu_stress", "Gate visível: GPU Stress 3D 720p",
                VisualSceneContract.GPU_STRESS_ID, 2, "", 3, 1, 3, 2, 0, true));
        output.add(new Step("emulator_frame", "Padrão de quadro de emulador",
                WorkloadContract.EMULATOR_FRAME_ID, 2, "", 7, 2, 10, 3, 70, false));
        output.add(new Step("shader_compile", "Compilação cold de shaders",
                WorkloadContract.SHADER_COMPILE_ID, 2, "", 5, 1, 1, 2, 30, false));
        output.add(new Step("trace_mixed", "Gate: trace gráfico, compute e barreiras",
                WorkloadContract.TRACE_REPLAY_ID, 2, TraceReplayContract.MIXED_TRACE_ID,
                3, 2, 3, 2, 0, true));
        output.add(new Step("correctness_post", "Correção offscreen após carga",
                WorkloadContract.RENDER_CORRECTNESS_ID, 1, "", 3, 0, 1, 0, 0, true));
        return Collections.unmodifiableList(output);
    }

    /**
     * v8 differs from v7 in one field: emulator_frame_pattern moves to workload
     * version 3, whose primary metric is composite_frame_ms — the sum of the
     * per-pass medians, which keeps the frame composition fixed. The v2 metric
     * pooled the five passes and moved with the sample mix, which is why the step
     * kept failing the linearity gate while carrying 70% of the weight.
     */
    private static List<Step> emulatorV8Steps() {
        List<Step> output = new ArrayList<>();
        for (Step step : emulatorV7Steps()) {
            if (WorkloadContract.EMULATOR_FRAME_ID.equals(step.workloadId)) {
                output.add(new Step(step.stepId, step.label, step.workloadId,
                        WorkloadContract.EMULATOR_FRAME_VERSION, step.traceId, step.rounds,
                        step.warmupSeconds, step.measureSeconds, step.cooldownSeconds,
                        step.weight, step.compatibilityGate));
            } else {
                output.add(step);
            }
        }
        return Collections.unmodifiableList(output);
    }

    static JSONObject definition() throws Exception { return definitionForVersion(currentVersion()); }

    static JSONObject definitionForVersion(int version) throws Exception {
        JSONArray encodedSteps = new JSONArray();
        int totalWeight = 0;
        for (Step step : stepsForVersion(version)) {
            encodedSteps.put(step.toJson());
            totalWeight += step.weight;
        }
        String label = version == 1 ? Phase7Contract.PROFILE_LABEL
                : version == 2 ? Phase8Contract.FULL_PROFILE_LABEL
                : version == 3 ? Phase11Contract.PROFILE_LABEL
                : version == Phase15DynamicRangeContract.PROFILE_VERSION
                ? Phase15DynamicRangeContract.PROFILE_LABEL
                : version == Phase15DynamicRangeContract.EMULATOR_V7_PROFILE_VERSION
                ? Phase15DynamicRangeContract.EMULATOR_V7_PROFILE_LABEL
                : version == Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION
                ? Phase15DynamicRangeContract.LEGACY_PROFILE_LABEL
                : Phase13ValidationContract.profileLabelForVersion(version);
        String limitations = version == 1 ? Phase7Contract.LIMITATION
                : version == 2 ? Phase8Contract.LIMITATION
                : version == 3 ? Phase11Contract.LIMITATION
                : version == Phase15DynamicRangeContract.PROFILE_VERSION
                || version == Phase15DynamicRangeContract.EMULATOR_V7_PROFILE_VERSION
                || version == Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION
                ? Phase15DynamicRangeContract.LIMITATION
                : Phase13ValidationContract.limitationForVersion(version);
        JSONObject definition = new JSONObject()
                .put("profile_id", Phase7Contract.PROFILE_ID)
                .put("profile_version", version)
                .put("label", label)
                .put("mode", version >= 4 ? "ab_reference_vs_candidate" : "ab_system_vs_candidate")
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
        if (version >= 3) {
            definition.put("automated_logical_test_count", version >= 4
                            ? Phase13ValidationContract.automatedLogicalTestsForVersion(version)
                            : Phase11Contract.AUTOMATED_LOGICAL_TESTS)
                    .put("optional_evidence_slot_count",
                            Phase11Contract.OPTIONAL_EVIDENCE_SLOTS)
                    .put("telemetry_attachment_optional", true)
                    .put("performance_and_compatibility_indices_separate", true);
        }
        definition.put("profile_sha256", JsonCanonicalizer.sha256WithoutKey(
                definition, "profile_sha256"));
        return definition;
    }

    static boolean verify(JSONObject profile) {
        try {
            if (!Phase7Contract.PROFILE_ID.equals(profile.optString("profile_id"))) return false;
            int version = profile.optInt("profile_version", -1);
            if (version != 1 && version != 2 && version != 3
                    && version != Phase13ValidationContract.LEGACY_PROFILE_VERSION
                    && version != Phase13ValidationContract.PROFILE_VERSION
                    && version != Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION
                    && version != Phase15DynamicRangeContract.EMULATOR_V7_PROFILE_VERSION
                    && version != Phase15DynamicRangeContract.PROFILE_VERSION) return false;
            JSONArray steps = profile.optJSONArray("steps");
            if (steps == null || steps.length() != stepsForVersion(version).size()) return false;
            String expected = profile.optString("profile_sha256", "");
            if (expected.length() != 64 || !expected.equalsIgnoreCase(
                    JsonCanonicalizer.sha256WithoutKey(profile, "profile_sha256"))) return false;
            return expected.equalsIgnoreCase(
                    definitionForVersion(version).optString("profile_sha256"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static Step step(String stepId) { return step(currentVersion(), stepId); }
    static Step step(int profileVersion, String stepId) {
        for (Step step : stepsForVersion(profileVersion)) {
            if (step.stepId.equals(stepId)) return step;
        }
        return null;
    }
}
