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
    static final int VERSION = 1;
    static final int WIDTH = 960;
    static final int HEIGHT = 540;
    static final int INSTANCE_COUNT = 144;
    static final int DEFAULT_PIXEL_TOLERANCE = 3;
    static final int DEFAULT_MAX_DIVERGENT_BLOCKS = 2;
    static final int BLOCK_SIZE = 24;
    static final double MINIMUM_BLOCK_MATCH_PERCENT = 99.0;
    static final String PRIMARY_METRIC = "p99_gpu_frame_ms";
    static final int[] CHECKPOINT_FRAMES = {30, 90, 150};
    static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            GEOMETRY_ID, MATERIALS_ID, POSTPROCESS_ID));

    private VisualSceneContract() {}

    static boolean isVisualScene(String workloadId) {
        return IDS.contains(workloadId);
    }

    static String labelFor(String workloadId) {
        if (GEOMETRY_ID.equals(workloadId)) return "Cena visível · geometria e depth v1";
        if (MATERIALS_ID.equals(workloadId)) return "Cena visível · materiais procedurais v1";
        if (POSTPROCESS_ID.equals(workloadId)) return "Cena visível · pós-processamento v1";
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
        return "Renderiza uma cena intermediária e um passe final com múltiplas amostras, bloom, "
                + "tone mapping e sincronização entre passes; não reproduz um pipeline temporal "
                + "completo de jogo. " + Phase8Contract.LIMITATION;
    }

    static JSONObject definition(String workloadId) throws Exception {
        JSONObject definition = new JSONObject()
                .put("scene_id", workloadId)
                .put("scene_version", VERSION)
                .put("label", labelFor(workloadId))
                .put("internal_width", WIDTH)
                .put("internal_height", HEIGHT)
                .put("instance_count", INSTANCE_COUNT)
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
        definition.put("definition_sha256",
                JsonCanonicalizer.sha256WithoutKey(definition, "definition_sha256"));
        return definition;
    }

    static JSONObject workloadConfig(String workloadId, int warmupSeconds, int measureSeconds,
                                     int pixelTolerance, int maximumDivergentBlocks)
            throws Exception {
        return new JSONObject()
                .put("warmup_seconds", warmupSeconds)
                .put("measure_seconds", measureSeconds)
                .put("primary_metric", PRIMARY_METRIC)
                .put("scene", definition(workloadId))
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
