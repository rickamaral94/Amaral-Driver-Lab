package com.amaral.driverlab;

import org.json.JSONObject;

final class Phase6Contract {
    static final int CAMPAIGN_SCHEMA_VERSION = 1;
    static final int SCHEDULER_VERSION = 1;
    static final int SUMMARY_VERSION = 1;
    static final int MAX_DRIVERS = 8;
    static final int MAX_WORKLOAD_SPECS = 8;
    static final int MAX_JOBS = 64;
    static final int MAX_COOLDOWN_SECONDS = 300;
    static final String ORDER_POLICY = "rotating_serpentine_v1";

    static final String LIMITATION =
            "Campanhas automatizam suítes sintéticas A/B no mesmo aparelho. A ordem reduz, mas "
                    + "não elimina, viés térmico e temporal; retomadas repetem o job interrompido. "
                    + "Rankings permanecem separados por hardware, workload, versão, configuração "
                    + "e analysis_version. Não existe pontuação composta entre workloads e nenhum "
                    + "resultado equivale a FPS ou compatibilidade garantida em jogos.";

    private Phase6Contract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("campaign_schema_version", CAMPAIGN_SCHEMA_VERSION)
                .put("campaign_scheduler_version", SCHEDULER_VERSION)
                .put("campaign_summary_version", SUMMARY_VERSION)
                .put("maximum_drivers", MAX_DRIVERS)
                .put("maximum_workload_specs", MAX_WORKLOAD_SPECS)
                .put("maximum_jobs", MAX_JOBS)
                .put("maximum_cooldown_seconds", MAX_COOLDOWN_SECONDS)
                .put("order_policy", ORDER_POLICY)
                .put("resume_policy", "running_job_returns_to_pending")
                .put("cross_workload_score", false)
                .put("limitations", LIMITATION);
    }
}
