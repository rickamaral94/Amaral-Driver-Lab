package com.amaral.driverlab;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class WorkloadContract {
    static final int RESULT_SCHEMA_VERSION = 8;

    static final int STATISTICAL_ANALYSIS_VERSION = 1;
    static final int BOOTSTRAP_ITERATIONS = 5_000;
    static final int MINIMUM_PAIRED_SAMPLES = 5;
    static final double CONFIDENCE_LEVEL = 0.95;
    static final double PRACTICAL_EQUIVALENCE_MARGIN_PERCENT = 3.0;
    static final String STATISTICAL_ANALYSIS_LIMITATION =
            "Reamostra rodadas A/B completas, não frames ou dispatches; com poucas "
                    + "rodadas o intervalo pode ser amplo, não há correção entre workloads e "
                    + "o resultado não representa FPS ou ganho em jogos.";

    static final String TRANSFER_ID = "vulkan_transfer_stress";
    static final int TRANSFER_VERSION = 1;
    static final String TRANSFER_NATIVE_NAME = "vulkan_transfer_stress_v1";
    static final String TRANSFER_METRIC = "transfer_payload_gib_s";
    static final String TRANSFER_LIMITATION =
            "Mede somente a carga sintética fill/copy; não representa largura de banda física "
                    + "da VRAM nem ganho em jogos.";

    static final String RENDER_CORRECTNESS_ID = "render_correctness_offscreen";
    static final int RENDER_CORRECTNESS_VERSION = 1;
    static final String RENDER_CORRECTNESS_LIMITATION =
            "Valida uma cena offscreen fixa; não prova desempenho em jogos nem correção em "
                    + "outros shaders, APIs ou workloads.";

    static final String SHADER_COMPILE_ID = "shader_compile_pipeline";
    static final int SHADER_COMPILE_VERSION = 1;
    static final String SHADER_COMPILE_METRIC = "cold_total_ms";
    static final String SHADER_COMPILE_LIMITATION =
            "Mede criação de pipelines para um conjunto SPIR-V fixo; não reproduz todos os "
                    + "shaders, caches ou padrões de stutter de jogos e emuladores.";

    static final String RENDERPASS_TILING_ID = "renderpass_tiling_gmem";
    static final int RENDERPASS_TILING_VERSION = 1;
    static final String RENDERPASS_TILING_METRIC = "median_frame_ms";
    static final String RENDERPASS_TILING_LIMITATION =
            "Estressa muitos draws, attachments, MSAA e caminhos sensíveis a depth/LRZ; "
                    + "não confirma o estado interno de LRZ/GMEM nem prevê FPS em jogos.";

    static final String COMPUTE_ARITHMETIC_ID = "compute_arithmetic";
    static final int COMPUTE_ARITHMETIC_VERSION = 1;
    static final String COMPUTE_ARITHMETIC_METRIC = "throughput_gops";
    static final String COMPUTE_ARITHMETIC_LIMITATION =
            "Mede uma carga aritmética compute fixa e validada; não representa transferência, "
                    + "IA, física, shaders gráficos ou desempenho geral da GPU.";

    static final String STABLE_SCENE_ID = "stable_scene_frametime";
    static final int STABLE_SCENE_VERSION = 1;
    static final String STABLE_SCENE_METRIC = "p99_frame_ms";
    static final String STABLE_SCENE_LIMITATION =
            "Mede a distribuição de frametime de uma cena offscreen estável; não inclui CPU, "
                    + "I/O, compilação dinâmica de jogos nem prova ganho de FPS real.";

    static final String THERMAL_SUSTAIN_ID = "thermal_sustain_efficiency";
    static final int THERMAL_SUSTAIN_VERSION = 1;
    static final String THERMAL_SUSTAIN_METRIC = "sustained_throughput_gops";

    static final String TRACE_REPLAY_ID = "vulkan_command_trace_replay";
    static final int TRACE_REPLAY_VERSION = 1;
    static final String TRACE_REPLAY_METRIC = "median_replay_ms";
    static final String TRACE_REPLAY_LIMITATION =
            "Reexecuta command traces Vulkan próprios e determinísticos do APK; não importa "
                    + "capturas de jogos, não reproduz CPU/I/O de emuladores e não garante FPS ou "
                    + "compatibilidade em aplicações reais.";

    static final String THERMAL_SUSTAIN_LIMITATION =
            "Mede sustentação de uma carga compute fixa e energia do aparelho inteiro; sensores "
                    + "podem ser ausentes e o resultado não representa autonomia ou jogos.";

    static final int RENDER_WIDTH = 256;
    static final int RENDER_HEIGHT = 256;
    static final int DEFAULT_PIXEL_TOLERANCE = 2;
    static final int BLOCK_SIZE = 16;
    static final double MINIMUM_BLOCK_MATCH_PERCENT = 99.0;
    static final int DEFAULT_MAX_DIVERGENT_BLOCKS = 0;

    static final int SHADER_PIPELINE_COUNT = 24;
    static final int TILING_DRAW_COUNT = 2048;
    static final int TILING_WIDTH = 640;
    static final int TILING_HEIGHT = 360;
    static final int COMPUTE_ELEMENT_COUNT = 1 << 20;
    static final int COMPUTE_ITERATIONS = 256;
    static final int STABLE_SCENE_WIDTH = 640;
    static final int STABLE_SCENE_HEIGHT = 360;
    static final int STABLE_SCENE_DRAWS = 512;
    static final int THERMAL_WINDOW_SECONDS = 5;
    static final int THERMAL_MIN_SECONDS = 30;
    static final int THERMAL_MAX_SECONDS = 900;

    static final List<String> PHASE2_IDS = Collections.unmodifiableList(Arrays.asList(
            SHADER_COMPILE_ID,
            RENDERPASS_TILING_ID,
            COMPUTE_ARITHMETIC_ID,
            STABLE_SCENE_ID,
            THERMAL_SUSTAIN_ID));

    private WorkloadContract() {}

    static boolean isSupported(String workloadId) {
        return TRANSFER_ID.equals(workloadId)
                || RENDER_CORRECTNESS_ID.equals(workloadId)
                || TRACE_REPLAY_ID.equals(workloadId)
                || PHASE2_IDS.contains(workloadId);
    }

    static boolean isPhase2(String workloadId) {
        return PHASE2_IDS.contains(workloadId);
    }

    static boolean isPerformance(String workloadId) {
        return TRANSFER_ID.equals(workloadId) || TRACE_REPLAY_ID.equals(workloadId)
                || isPhase2(workloadId);
    }

    static int versionFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return TRANSFER_VERSION;
        if (RENDER_CORRECTNESS_ID.equals(workloadId)) return RENDER_CORRECTNESS_VERSION;
        if (SHADER_COMPILE_ID.equals(workloadId)) return SHADER_COMPILE_VERSION;
        if (RENDERPASS_TILING_ID.equals(workloadId)) return RENDERPASS_TILING_VERSION;
        if (COMPUTE_ARITHMETIC_ID.equals(workloadId)) return COMPUTE_ARITHMETIC_VERSION;
        if (STABLE_SCENE_ID.equals(workloadId)) return STABLE_SCENE_VERSION;
        if (THERMAL_SUSTAIN_ID.equals(workloadId)) return THERMAL_SUSTAIN_VERSION;
        if (TRACE_REPLAY_ID.equals(workloadId)) return TRACE_REPLAY_VERSION;
        throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
    }

    static String limitationFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return TRANSFER_LIMITATION;
        if (RENDER_CORRECTNESS_ID.equals(workloadId)) return RENDER_CORRECTNESS_LIMITATION;
        if (SHADER_COMPILE_ID.equals(workloadId)) return SHADER_COMPILE_LIMITATION;
        if (RENDERPASS_TILING_ID.equals(workloadId)) return RENDERPASS_TILING_LIMITATION;
        if (COMPUTE_ARITHMETIC_ID.equals(workloadId)) return COMPUTE_ARITHMETIC_LIMITATION;
        if (STABLE_SCENE_ID.equals(workloadId)) return STABLE_SCENE_LIMITATION;
        if (THERMAL_SUSTAIN_ID.equals(workloadId)) return THERMAL_SUSTAIN_LIMITATION;
        if (TRACE_REPLAY_ID.equals(workloadId)) return TRACE_REPLAY_LIMITATION;
        throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
    }

    static String labelFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return "transferência fill/copy v1";
        if (RENDER_CORRECTNESS_ID.equals(workloadId)) return "correção offscreen v1";
        if (SHADER_COMPILE_ID.equals(workloadId)) return "compilação de shaders v1";
        if (RENDERPASS_TILING_ID.equals(workloadId)) return "render pass / tiling v1";
        if (COMPUTE_ARITHMETIC_ID.equals(workloadId)) return "compute aritmético v1";
        if (STABLE_SCENE_ID.equals(workloadId)) return "frametime estável v1";
        if (THERMAL_SUSTAIN_ID.equals(workloadId)) return "sustentação térmica v1";
        if (TRACE_REPLAY_ID.equals(workloadId)) return "trace replay Vulkan v1";
        throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
    }

    static String nativeNameFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return TRANSFER_NATIVE_NAME;
        if (TRACE_REPLAY_ID.equals(workloadId)) return "vulkan_command_trace_replay_v1";
        return workloadId + "_v" + versionFor(workloadId);
    }

    static String primaryMetricFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return TRANSFER_METRIC;
        if (SHADER_COMPILE_ID.equals(workloadId)) return SHADER_COMPILE_METRIC;
        if (RENDERPASS_TILING_ID.equals(workloadId)) return RENDERPASS_TILING_METRIC;
        if (COMPUTE_ARITHMETIC_ID.equals(workloadId)) return COMPUTE_ARITHMETIC_METRIC;
        if (STABLE_SCENE_ID.equals(workloadId)) return STABLE_SCENE_METRIC;
        if (THERMAL_SUSTAIN_ID.equals(workloadId)) return THERMAL_SUSTAIN_METRIC;
        if (TRACE_REPLAY_ID.equals(workloadId)) return TRACE_REPLAY_METRIC;
        throw new IllegalArgumentException("Workload sem métrica primária: " + workloadId);
    }

    static boolean lowerIsBetter(String workloadId) {
        return SHADER_COMPILE_ID.equals(workloadId)
                || RENDERPASS_TILING_ID.equals(workloadId)
                || STABLE_SCENE_ID.equals(workloadId)
                || TRACE_REPLAY_ID.equals(workloadId);
    }

    static long timeoutSeconds(String workloadId, int warmupSeconds, int measureSeconds) {
        if (RENDER_CORRECTNESS_ID.equals(workloadId)) return 90L;
        if (THERMAL_SUSTAIN_ID.equals(workloadId)) return warmupSeconds + measureSeconds + 90L;
        if (SHADER_COMPILE_ID.equals(workloadId)) return 180L;
        if (TRACE_REPLAY_ID.equals(workloadId)) return warmupSeconds + measureSeconds + 120L;
        return warmupSeconds + measureSeconds + 60L;
    }
}
