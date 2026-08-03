package com.amaral.driverlab;

final class WorkloadContract {
    static final int RESULT_SCHEMA_VERSION = 2;

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

    static final int RENDER_WIDTH = 256;
    static final int RENDER_HEIGHT = 256;
    static final int DEFAULT_PIXEL_TOLERANCE = 2;
    static final int BLOCK_SIZE = 16;
    static final double MINIMUM_BLOCK_MATCH_PERCENT = 99.0;
    static final int DEFAULT_MAX_DIVERGENT_BLOCKS = 8;

    private WorkloadContract() {}

    static boolean isSupported(String workloadId) {
        return TRANSFER_ID.equals(workloadId) || RENDER_CORRECTNESS_ID.equals(workloadId);
    }

    static int versionFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return TRANSFER_VERSION;
        if (RENDER_CORRECTNESS_ID.equals(workloadId)) return RENDER_CORRECTNESS_VERSION;
        throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
    }

    static String limitationFor(String workloadId) {
        if (TRANSFER_ID.equals(workloadId)) return TRANSFER_LIMITATION;
        if (RENDER_CORRECTNESS_ID.equals(workloadId)) return RENDER_CORRECTNESS_LIMITATION;
        throw new IllegalArgumentException("Workload desconhecido: " + workloadId);
    }
}
