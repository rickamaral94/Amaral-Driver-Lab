package com.amaral.driverlab;

import org.json.JSONObject;

/** Short practical profile used by the Phase 13 home screen. */
final class Phase13ValidationContract {
    static final int LEGACY_PROFILE_VERSION = 4;
    static final int PROFILE_VERSION = 5;
    static final int LEGACY_AUTOMATED_LOGICAL_TESTS = 8;
    static final int AUTOMATED_ORCHESTRATED_STEPS = 9;
    static final int AUTOMATED_LOGICAL_TESTS = 9;
    static final int LEGACY_MINIMUM_VALID_PERFORMANCE_CATEGORIES = 5;
    static final int MINIMUM_VALID_PERFORMANCE_CATEGORIES = 6;
    static final int LEGACY_HIGH_CONFIDENCE_PERFORMANCE_CATEGORIES = 6;
    static final int HIGH_CONFIDENCE_PERFORMANCE_CATEGORIES = 7;
    static final int LEGACY_HIGH_CONFIDENCE_CONCLUSIVE_CATEGORIES = 4;
    static final int HIGH_CONFIDENCE_CONCLUSIVE_CATEGORIES = 5;
    static final int LEGACY_MEDIUM_CONFIDENCE_PERFORMANCE_CATEGORIES = 5;
    static final int MEDIUM_CONFIDENCE_PERFORMANCE_CATEGORIES = 6;
    static final int LEGACY_MEDIUM_CONFIDENCE_CONCLUSIVE_CATEGORIES = 3;
    static final int MEDIUM_CONFIDENCE_CONCLUSIVE_CATEGORIES = 4;
    static final String LEGACY_PROFILE_LABEL =
            "Turnip Recommended Validation v1 · comparação prática";
    static final String PROFILE_LABEL =
            "Turnip Recommended Validation v2 · GPU Stress 3D";
    static final String LEGACY_LIMITATION =
            "Perfil curto para escolher entre drivers Turnip no uso diário. Valida correção "
                    + "offscreen antes e depois da carga, três cenas visuais, compilação de "
                    + "shaders, frametime de cena estável e um trace misto de gráficos, compute "
                    + "e barreiras. Não substitui o Full Qualification v3 em investigação de "
                    + "falhas raras, pressão de memória, diagnóstico profundo, thermal sustain "
                    + "ou soak prolongado.";
    static final String LIMITATION =
            "Perfil curto para escolher entre drivers Turnip no uso diário. Valida correção "
                    + "offscreen antes e depois da carga, três cenas visuais legadas, GPU Stress "
                    + "3D em 1280×720, compilação de shaders, frametime de cena estável e um trace "
                    + "misto de gráficos, compute e barreiras. A carga sintética não equivale ao "
                    + "FPS de jogos. Não substitui o Full Qualification v3 em investigação de "
                    + "falhas raras, pressão de memória, diagnóstico profundo, thermal sustain "
                    + "ou soak prolongado.";

    private Phase13ValidationContract() {}

    static JSONObject contractJson() throws Exception {
        return contractJson(PROFILE_VERSION);
    }

    static JSONObject contractJson(int version) throws Exception {
        boolean legacy = version == LEGACY_PROFILE_VERSION;
        if (!legacy && version != PROFILE_VERSION) {
            throw new IllegalArgumentException("Versão Recommended desconhecida: " + version);
        }
        org.json.JSONArray keptTests = new org.json.JSONArray()
                .put("render_correctness_pre_post")
                .put("visual_geometry_depth")
                .put("visual_materials")
                .put("visual_postprocess");
        if (!legacy) keptTests.put("visual_gpu_stress_3d_720p");
        keptTests.put("shader_compile")
                .put("stable_scene_frametime")
                .put("mixed_trace_synchronization");
        return new JSONObject()
                .put("profile_version", version)
                .put("profile_label", profileLabelForVersion(version))
                .put("automated_orchestrated_steps", legacy
                        ? LEGACY_AUTOMATED_LOGICAL_TESTS : AUTOMATED_ORCHESTRATED_STEPS)
                .put("automated_logical_tests", automatedLogicalTestsForVersion(version))
                .put("minimum_valid_performance_categories",
                        minimumValidPerformanceCategoriesForVersion(version))
                .put("kept_tests", keptTests)
                .put("moved_to_advanced_full_v3", new org.json.JSONArray()
                        .put("renderpass_tiling_synthetic")
                        .put("compute_arithmetic_synthetic")
                        .put("transfer_fill_copy")
                        .put("compute_chain_trace")
                        .put("thermal_sustain_30s")
                        .put("deep_diagnostics")
                        .put("five_cycle_soak"))
                .put("legacy_recommended_profile_v4_preserved", true)
                .put("limitations", limitationForVersion(version));
    }

    static String profileLabelForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION ? LEGACY_PROFILE_LABEL : PROFILE_LABEL;
    }

    static String limitationForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION ? LEGACY_LIMITATION : LIMITATION;
    }

    static int automatedLogicalTestsForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION
                ? LEGACY_AUTOMATED_LOGICAL_TESTS : AUTOMATED_LOGICAL_TESTS;
    }

    static int minimumValidPerformanceCategoriesForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION
                ? LEGACY_MINIMUM_VALID_PERFORMANCE_CATEGORIES
                : MINIMUM_VALID_PERFORMANCE_CATEGORIES;
    }

    static int highConfidencePerformanceCategoriesForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION
                ? LEGACY_HIGH_CONFIDENCE_PERFORMANCE_CATEGORIES
                : HIGH_CONFIDENCE_PERFORMANCE_CATEGORIES;
    }

    static int highConfidenceConclusiveCategoriesForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION
                ? LEGACY_HIGH_CONFIDENCE_CONCLUSIVE_CATEGORIES
                : HIGH_CONFIDENCE_CONCLUSIVE_CATEGORIES;
    }

    static int mediumConfidencePerformanceCategoriesForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION
                ? LEGACY_MEDIUM_CONFIDENCE_PERFORMANCE_CATEGORIES
                : MEDIUM_CONFIDENCE_PERFORMANCE_CATEGORIES;
    }

    static int mediumConfidenceConclusiveCategoriesForVersion(int version) {
        return version == LEGACY_PROFILE_VERSION
                ? LEGACY_MEDIUM_CONFIDENCE_CONCLUSIVE_CATEGORIES
                : MEDIUM_CONFIDENCE_CONCLUSIVE_CATEGORIES;
    }
}
