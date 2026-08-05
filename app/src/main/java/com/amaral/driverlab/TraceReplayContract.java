package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TraceReplayContract {
    static final int TRACE_FORMAT_VERSION = 1;
    static final int TRACE_ANALYSIS_VERSION = 1;
    static final String MIXED_TRACE_ID = "mixed_graphics_compute_barrier";
    static final String COMPUTE_CHAIN_TRACE_ID = "compute_dependency_chain";
    static final int TRACE_VERSION = 1;
    static final int GRAPHICS_WIDTH = 320;
    static final int GRAPHICS_HEIGHT = 180;
    static final int COMPUTE_WORD_COUNT = 65_536;
    static final int MINIMUM_SAMPLES_PER_PHASE = 8;
    static final int MAXIMUM_SAMPLES_PER_PHASE = 500;
    static final String LIMITATION =
            "O formato v1 descreve somente traces próprios e imutáveis do APK. Ele não é "
                    + "compatível com RenderDoc, gfxreconstruct ou capturas de jogos e não "
                    + "reproduz CPU, áudio, I/O, compilação dinâmica ou lógica de emuladores.";

    static final List<String> TRACE_IDS = Collections.unmodifiableList(Arrays.asList(
            MIXED_TRACE_ID, COMPUTE_CHAIN_TRACE_ID));

    private TraceReplayContract() {}

    static boolean isSupported(String traceId) {
        return TRACE_IDS.contains(traceId);
    }

    static String labelFor(String traceId) {
        if (MIXED_TRACE_ID.equals(traceId)) {
            return "Misto: render pass + compute + barreiras v1";
        }
        if (COMPUTE_CHAIN_TRACE_ID.equals(traceId)) {
            return "Compute: cadeia de dependências v1";
        }
        throw new IllegalArgumentException("Trace desconhecido: " + traceId);
    }

    static JSONObject definition(String traceId) throws Exception {
        if (!isSupported(traceId)) throw new IllegalArgumentException("Trace desconhecido: " + traceId);
        JSONObject definition = new JSONObject();
        definition.put("trace_id", traceId);
        definition.put("trace_version", TRACE_VERSION);
        definition.put("trace_format_version", TRACE_FORMAT_VERSION);
        definition.put("immutable", true);
        definition.put("primary_metric", WorkloadContract.TRACE_REPLAY_METRIC);
        definition.put("lower_is_better", true);
        definition.put("output_comparison", "exact_sha256_per_paired_round");
        definition.put("minimum_samples_per_phase", MINIMUM_SAMPLES_PER_PHASE);
        definition.put("maximum_samples_per_phase", MAXIMUM_SAMPLES_PER_PHASE);
        JSONArray operations = new JSONArray();
        if (MIXED_TRACE_ID.equals(traceId)) {
            operations.put("seed_buffer_copy");
            operations.put("host_to_compute_barrier");
            operations.put("render_pass_clear");
            operations.put("graphics_pipeline_bind");
            operations.put("draw_64_triangles");
            operations.put("color_attachment_to_transfer_barrier");
            operations.put("compute_pipeline_bind");
            operations.put("dispatch_4_with_dependencies");
            operations.put("compute_to_transfer_barrier");
            operations.put("image_and_buffer_readback_copy");
            definition.put("graphics_width", GRAPHICS_WIDTH);
            definition.put("graphics_height", GRAPHICS_HEIGHT);
            definition.put("draw_count", 64);
            definition.put("dispatch_count", 4);
            definition.put("output_kind", "rgba8_plus_u32");
        } else {
            operations.put("seed_buffer_copy");
            operations.put("transfer_to_compute_barrier");
            operations.put("compute_pipeline_bind");
            operations.put("dispatch_12_with_dependencies");
            operations.put("compute_to_transfer_barrier");
            operations.put("buffer_readback_copy");
            definition.put("draw_count", 0);
            definition.put("dispatch_count", 12);
            definition.put("output_kind", "u32");
        }
        definition.put("compute_word_count", COMPUTE_WORD_COUNT);
        definition.put("operations", operations);
        definition.put("limitations", LIMITATION);
        definition.put("definition_sha256", JsonCanonicalizer.sha256(definition));
        return definition;
    }

    static JSONObject contractJson(String traceId) throws Exception {
        return new JSONObject()
                .put("trace_analysis_version", TRACE_ANALYSIS_VERSION)
                .put("trace_format_version", TRACE_FORMAT_VERSION)
                .put("selected_trace", definition(traceId))
                .put("correctness_gate", "exact_output_sha256_before_performance_verdict")
                .put("isolation", "fresh_runner_process_per_arm")
                .put("order_policy", "paired_AB_BA_alternating")
                .put("limitations", LIMITATION);
    }
}
