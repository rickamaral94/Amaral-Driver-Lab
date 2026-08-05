package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class Phase10Contract {
    static final int DEEP_DIAGNOSTIC_SCHEMA_VERSION = 1;
    static final int REPORT_VERSION = 1;
    static final int COMPARISON_VERSION = 1;
    static final int BUNDLE_VERSION = 1;
    static final int FORMAT_MATRIX_VERSION = 1;
    static final int SHADER_CORPUS_VERSION = 1;
    static final int PIPELINE_CACHE_VERSION = 1;
    static final int MEMORY_PRESSURE_VERSION = 1;
    static final int SYNCHRONIZATION_VERSION = 1;
    static final int SOAK_TEST_VERSION = 1;
    static final String PROFILE_ID = "turnip_deep_diagnostics";
    static final int PROFILE_VERSION = 1;
    static final int DEFAULT_SOAK_CYCLES = 10;
    static final int MIN_SOAK_CYCLES = 1;
    static final int MAX_SOAK_CYCLES = 50;
    static final int DEFAULT_MEMORY_MIB = 128;
    static final int MIN_MEMORY_MIB = 16;
    static final int MAX_MEMORY_MIB = 256;
    static final double PRACTICAL_MARGIN_PERCENT = 3.0;
    static final List<String> MODULE_IDS = Collections.unmodifiableList(Arrays.asList(
            "format_matrix",
            "shader_pipeline_corpus",
            "memory_pressure",
            "synchronization",
            "reliability_probe"));

    static final String LIMITATION =
            "Diagnóstico Vulkan sintético e controlado. A matriz não cobre todas as combinações "
                    + "de flags, o corpus v1 é compute, a pressão de memória usa limite seguro, "
                    + "a sincronização v1 usa uma família de fila e o soak não reproduz uma "
                    + "sessão longa de jogo ou emulador.";

    private Phase10Contract() {}

    static JSONObject contractJson() throws Exception {
        JSONArray modules = new JSONArray();
        for (String module : MODULE_IDS) modules.put(module);
        JSONObject profile = new JSONObject()
                .put("profile_id", PROFILE_ID)
                .put("profile_version", PROFILE_VERSION)
                .put("modules", modules)
                .put("default_soak_cycles", DEFAULT_SOAK_CYCLES)
                .put("maximum_soak_cycles", MAX_SOAK_CYCLES)
                .put("default_memory_mib", DEFAULT_MEMORY_MIB)
                .put("maximum_memory_mib", MAX_MEMORY_MIB)
                .put("practical_margin_percent", PRACTICAL_MARGIN_PERCENT);
        return new JSONObject()
                .put("deep_diagnostic_schema_version", DEEP_DIAGNOSTIC_SCHEMA_VERSION)
                .put("deep_diagnostic_report_version", REPORT_VERSION)
                .put("deep_diagnostic_comparison_version", COMPARISON_VERSION)
                .put("deep_diagnostic_bundle_version", BUNDLE_VERSION)
                .put("format_matrix_version", FORMAT_MATRIX_VERSION)
                .put("shader_corpus_version", SHADER_CORPUS_VERSION)
                .put("pipeline_cache_diagnostic_version", PIPELINE_CACHE_VERSION)
                .put("memory_pressure_version", MEMORY_PRESSURE_VERSION)
                .put("synchronization_version", SYNCHRONIZATION_VERSION)
                .put("soak_test_version", SOAK_TEST_VERSION)
                .put("profile", profile)
                .put("profile_sha256", JsonCanonicalizer.sha256(profile))
                .put("historical_series_separate", true)
                .put("changes_existing_workload_definitions", false)
                .put("limitations", LIMITATION);
    }

    static String profileSha256() throws Exception {
        return contractJson().getString("profile_sha256");
    }
}
