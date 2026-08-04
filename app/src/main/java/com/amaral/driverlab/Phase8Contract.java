package com.amaral.driverlab;

import org.json.JSONObject;

final class Phase8Contract {
    static final int VISUAL_SCENE_CONTRACT_VERSION = 1;
    static final int CHECKPOINT_ANALYSIS_VERSION = 1;
    static final int CURRENT_FULL_PROFILE_VERSION = 2;
    static final int CURRENT_QUALIFICATION_REPORT_VERSION = 2;
    static final int CURRENT_QUALIFICATION_SCORE_VERSION = 2;
    static final int MINIMUM_VALID_PERFORMANCE_STEPS_V2 = 8;
    static final String FULL_PROFILE_LABEL =
            "Turnip Full Qualification v2 · cenas Vulkan visíveis";

    static final String LIMITATION =
            "As cenas visuais são cargas Vulkan próprias, animadas e determinísticas, exibidas "
                    + "na tela e renderizadas em resolução interna fixa. Elas exercitam gráficos, "
                    + "depth, amostragem e pós-processamento, mas não são capturas de jogos e não "
                    + "reproduzem CPU, I/O, compositor ou shaders de emuladores reais.";

    private Phase8Contract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("visual_scene_contract_version", VISUAL_SCENE_CONTRACT_VERSION)
                .put("checkpoint_analysis_version", CHECKPOINT_ANALYSIS_VERSION)
                .put("current_full_profile_version", CURRENT_FULL_PROFILE_VERSION)
                .put("current_qualification_report_version",
                        CURRENT_QUALIFICATION_REPORT_VERSION)
                .put("current_qualification_score_version",
                        CURRENT_QUALIFICATION_SCORE_VERSION)
                .put("checkpoint_frames", VisualSceneContract.checkpointFramesJson())
                .put("internal_width", VisualSceneContract.WIDTH)
                .put("internal_height", VisualSceneContract.HEIGHT)
                .put("visible_surface_required", true)
                .put("legacy_full_profile_v1_preserved", true)
                .put("limitations", LIMITATION);
    }
}
