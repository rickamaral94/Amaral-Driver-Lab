package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class VisualSceneContract {
    static final String GEOMETRY_ID = "visual_scene_geometry";
    static final String MATERIALS_ID = "visual_scene_materials";
    static final String POSTPROCESS_ID = "visual_scene_postprocess";
    static final String GPU_STRESS_ID = "visual_scene_gpu_stress";
    static final int LEGACY_VERSION = 1;
    /**
     * v2 wrote the GPU timestamps around the whole command buffer, so the sample
     * included the checkpoint copy, the layout transitions and the full-surface
     * blit into the swapchain — work paid once per sample regardless of how many
     * repetitions were requested. The declared repetition unit is a render pass,
     * so the bracket did not match what it claimed to measure and the linearity
     * ratio came in under the 1.8 floor on materials (1.67) and postprocess (1.65).
     */
    static final int UNBRACKETED_VERSION = 2;
    static final int VERSION = 3;
    static final int WIDTH = 960;
    static final int HEIGHT = 540;
    static final int INSTANCE_COUNT = 144;
    static final int GPU_STRESS_WIDTH = 1280;
    static final int GPU_STRESS_HEIGHT = 720;
    static final int GPU_STRESS_INSTANCE_COUNT = 768;
    static final int GPU_STRESS_VERTICES_PER_INSTANCE = 36;
    static final int GPU_STRESS_POSTPROCESS_SAMPLES = 19;
    static final int DEFAULT_PIXEL_TOLERANCE = 3;
    static final int DEFAULT_MAX_DIVERGENT_BLOCKS = 2;
    static final int BLOCK_SIZE = 24;
    static final double MINIMUM_BLOCK_MATCH_PERCENT = 99.0;
    static final String PRIMARY_METRIC = "p99_gpu_frame_ms";
    static final int[] CHECKPOINT_FRAMES = {30, 90, 150};
    static final List<String> LEGACY_QUALIFICATION_IDS = Collections.unmodifiableList(Arrays.asList(
            GEOMETRY_ID, MATERIALS_ID, POSTPROCESS_ID));
    static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            GEOMETRY_ID, MATERIALS_ID, POSTPROCESS_ID, GPU_STRESS_ID));
    static final List<String> RECOMMENDED_QUALIFICATION_IDS = IDS;

    private VisualSceneContract() {}

    static boolean isVisualScene(String workloadId) {
        return IDS.contains(workloadId);
    }

    static String labelFor(String workloadId) {
        return labelFor(workloadId, VERSION);
    }

    static String labelFor(String workloadId, int version) {
        if (version != LEGACY_VERSION && version != UNBRACKETED_VERSION
                && version != VERSION) {
            throw new IllegalArgumentException("Versão de cena desconhecida: " + version);
        }
        if (GEOMETRY_ID.equals(workloadId)) return "Cena visível · geometria e depth v" + version;
        if (MATERIALS_ID.equals(workloadId)) return "Cena visível · materiais procedurais v" + version;
        if (POSTPROCESS_ID.equals(workloadId)) return "Cena visível · pós-processamento v" + version;
        if (GPU_STRESS_ID.equals(workloadId)) {
            return "Cena avançada: GPU Stress 3D v" + version;
        }
        throw new IllegalArgumentException("Cena visual desconhecida: " + workloadId);
    }

    static String limitationFor(String workloadId) {
        if (!isVisualScene(workloadId)) {
            throw new IllegalArgumentException("Cena visual desconhecida: " + workloadId);
        }
        if (GEOMETRY_ID.equals(workloadId)) {
            return "Renderiza 144 instâncias animadas com depth em uma superfície Android real; "
                    + "não representa a complexidade geométrica de um jogo completo. "
                    + Phase8Contract.LIMITATION;
        }
        if (MATERIALS_ID.equals(workloadId)) {
            return "Renderiza materiais procedurais, padrões de alta frequência e amostragem "
                    + "intermediária; não cobre todos os formatos e codecs de textura. "
                    + Phase8Contract.LIMITATION;
        }
        if (POSTPROCESS_ID.equals(workloadId)) {
            return "Renderiza uma cena intermediária e um passe final com múltiplas amostras, bloom, "
                    + "tone mapping e sincronização entre passes; não reproduz um pipeline temporal "
                    + "completo de jogo. " + Phase8Contract.LIMITATION;
        }
        return "Renderiza 768 cubos 3D procedurais em 1280×720, com depth, quatro luzes, "
                + "materiais multi-oitava e pós-processamento de 19 amostras. É uma carga "
                + "sintética mais pesada e não equivale ao FPS de um jogo ou emulador. "
                + Phase8Contract.LIMITATION;
    }

    static int widthFor(String workloadId) {
        requireVisualScene(workloadId);
        return GPU_STRESS_ID.equals(workloadId) ? GPU_STRESS_WIDTH : WIDTH;
    }

    static int heightFor(String workloadId) {
        requireVisualScene(workloadId);
        return GPU_STRESS_ID.equals(workloadId) ? GPU_STRESS_HEIGHT : HEIGHT;
    }

    static int instanceCountFor(String workloadId) {
        requireVisualScene(workloadId);
        return GPU_STRESS_ID.equals(workloadId) ? GPU_STRESS_INSTANCE_COUNT : INSTANCE_COUNT;
    }

    private static void requireVisualScene(String workloadId) {
        if (!isVisualScene(workloadId)) {
            throw new IllegalArgumentException("Cena visual desconhecida: " + workloadId);
        }
    }

    static JSONObject definition(String workloadId) throws Exception {
        return definition(workloadId, VERSION);
    }

    static JSONObject definition(String workloadId, int version) throws Exception {
        if (version != LEGACY_VERSION && version != UNBRACKETED_VERSION
                && version != VERSION) {
            throw new IllegalArgumentException("Versão de cena desconhecida: " + version);
        }
        JSONObject definition = new JSONObject()
                .put("scene_id", workloadId)
                .put("scene_version", version)
                .put("label", labelFor(workloadId, version))
                .put("internal_width", widthFor(workloadId))
                .put("internal_height", heightFor(workloadId))
                .put("instance_count", instanceCountFor(workloadId))
                .put("checkpoint_frames", checkpointFramesJson())
                .put("animation_clock", "fixed_frame_index_divided_by_60")
                .put("surface", "VK_KHR_android_surface")
                .put("primary_metric", PRIMARY_METRIC)
                .put("lower_is_better", true)
                .put("pixel_tolerance", DEFAULT_PIXEL_TOLERANCE)
                .put("block_size_px", BLOCK_SIZE)
                .put("minimum_block_match_percent", MINIMUM_BLOCK_MATCH_PERCENT)
                .put("maximum_divergent_blocks", DEFAULT_MAX_DIVERGENT_BLOCKS)
                .put("limitations", limitationFor(workloadId));
        if (GPU_STRESS_ID.equals(workloadId)) {
            definition.put("geometry_model", "procedural_instanced_cubes")
                    .put("vertices_per_instance", GPU_STRESS_VERTICES_PER_INSTANCE)
                    .put("light_count", 4)
                    .put("material_octaves", 6)
                    .put("render_pass_count", 2)
                    .put("postprocess_sample_count", GPU_STRESS_POSTPROCESS_SAMPLES)
                    .put("benchmark_tier", "advanced_gpu_stress");
        }
        definition.put("definition_sha256",
                JsonCanonicalizer.sha256WithoutKey(definition, "definition_sha256"));
        return definition;
    }

    static JSONObject workloadConfig(String workloadId, int warmupSeconds, int measureSeconds,
                                     int pixelTolerance, int maximumDivergentBlocks)
            throws Exception {
        return workloadConfig(workloadId, VERSION, warmupSeconds, measureSeconds,
                pixelTolerance, maximumDivergentBlocks);
    }

    static JSONObject workloadConfig(String workloadId, int version,
                                     int warmupSeconds, int measureSeconds,
                                     int pixelTolerance, int maximumDivergentBlocks)
            throws Exception {
        return new JSONObject()
                .put("warmup_seconds", warmupSeconds)
                .put("measure_seconds", measureSeconds)
                .put("primary_metric", PRIMARY_METRIC)
                .put("scene", definition(workloadId, version))
                .put("pixel_tolerance", pixelTolerance)
                .put("block_size_px", BLOCK_SIZE)
                .put("minimum_block_match_percent", MINIMUM_BLOCK_MATCH_PERCENT)
                .put("maximum_divergent_blocks", maximumDivergentBlocks);
    }

    static JSONArray checkpointFramesJson() {
        JSONArray output = new JSONArray();
        for (int frame : CHECKPOINT_FRAMES) output.put(frame);
        return output;
    }
}
