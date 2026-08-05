package com.amaral.driverlab;

import org.json.JSONObject;

/** Short practical profile used by the Phase 13 home screen. */
final class Phase13ValidationContract {
    static final int PROFILE_VERSION = 4;
    static final int AUTOMATED_ORCHESTRATED_STEPS = 8;
    static final int AUTOMATED_LOGICAL_TESTS = 8;
    static final int MINIMUM_VALID_PERFORMANCE_CATEGORIES = 5;
    static final int HIGH_CONFIDENCE_PERFORMANCE_CATEGORIES = 6;
    static final int HIGH_CONFIDENCE_CONCLUSIVE_CATEGORIES = 4;
    static final int MEDIUM_CONFIDENCE_PERFORMANCE_CATEGORIES = 5;
    static final int MEDIUM_CONFIDENCE_CONCLUSIVE_CATEGORIES = 3;
    static final String PROFILE_LABEL =
            "Turnip Recommended Validation v1 · comparação prática";
    static final String LIMITATION =
            "Perfil curto para escolher entre drivers Turnip no uso diário. Valida correção "
                    + "offscreen antes e depois da carga, três cenas visuais, compilação de "
                    + "shaders, frametime de cena estável e um trace misto de gráficos, compute "
                    + "e barreiras. Não substitui o Full Qualification v3 em investigação de "
                    + "falhas raras, pressão de memória, diagnóstico profundo, thermal sustain "
                    + "ou soak prolongado.";

    private Phase13ValidationContract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("profile_version", PROFILE_VERSION)
                .put("profile_label", PROFILE_LABEL)
                .put("automated_orchestrated_steps", AUTOMATED_ORCHESTRATED_STEPS)
                .put("automated_logical_tests", AUTOMATED_LOGICAL_TESTS)
                .put("minimum_valid_performance_categories",
                        MINIMUM_VALID_PERFORMANCE_CATEGORIES)
                .put("kept_tests", new org.json.JSONArray()
                        .put("render_correctness_pre_post")
                        .put("visual_geometry_depth")
                        .put("visual_materials")
                        .put("visual_postprocess")
                        .put("shader_compile")
                        .put("stable_scene_frametime")
                        .put("mixed_trace_synchronization"))
                .put("moved_to_advanced_full_v3", new org.json.JSONArray()
                        .put("renderpass_tiling_synthetic")
                        .put("compute_arithmetic_synthetic")
                        .put("transfer_fill_copy")
                        .put("compute_chain_trace")
                        .put("thermal_sustain_30s")
                        .put("deep_diagnostics")
                        .put("five_cycle_soak"))
                .put("limitations", LIMITATION);
    }
}
