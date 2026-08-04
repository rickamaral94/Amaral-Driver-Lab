package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

final class QualificationStore {
    private QualificationStore() {}

    static File create(File filesDir, DriverPackage driver, JSONObject preflight) throws Exception {
        return create(filesDir, driver, null, "system_vs_turnip",
                QualificationProfile.currentVersion(), preflight);
    }

    static File create(File filesDir, DriverPackage driver, DriverPackage referenceDriver,
                       String comparisonMode, JSONObject preflight) throws Exception {
        return create(filesDir, driver, referenceDriver, comparisonMode,
                QualificationProfile.currentVersion(), preflight);
    }

    static File create(File filesDir, DriverPackage driver, DriverPackage referenceDriver,
                       String comparisonMode, int profileVersion, JSONObject preflight)
            throws Exception {
        String normalizedMode = "turnip_vs_turnip".equals(comparisonMode)
                ? "turnip_vs_turnip" : "system_vs_turnip";
        if ("turnip_vs_turnip".equals(normalizedMode)) {
            if (referenceDriver == null || !referenceDriver.isUsable()) {
                throw new IllegalArgumentException("Driver de referência inválido");
            }
            if (driver.sha256.equalsIgnoreCase(referenceDriver.sha256)) {
                throw new IllegalArgumentException("Candidato e referência devem ser diferentes");
            }
        }
        long now = System.currentTimeMillis();
        String id = "qualification-" + now;
        File directory = new File(new File(filesDir, "qualifications"), id);
        if (!directory.mkdirs()) throw new IllegalStateException("Não foi possível criar " + id);
        JSONObject profile = QualificationProfile.definitionForVersion(profileVersion);
        JSONArray states = new JSONArray();
        for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(profileVersion)) {
            states.put(new JSONObject()
                    .put("step_id", step.stepId)
                    .put("step_kind", step.kind)
                    .put("status", "pending")
                    .put("attempt_count", 0)
                    .put("started_at_ms", JSONObject.NULL)
                    .put("finished_at_ms", JSONObject.NULL)
                    .put("suite_relative_path", JSONObject.NULL)
                    .put("suite_id", JSONObject.NULL)
                    .put("artifact_relative_path", JSONObject.NULL)
                    .put("artifact_id", JSONObject.NULL)
                    .put("result_type", JSONObject.NULL)
                    .put("failure", JSONObject.NULL));
        }
        JSONObject manifest = new JSONObject()
                .put("qualification_schema_version", Phase11Contract.QUALIFICATION_SCHEMA_VERSION)
                .put("qualification_id", id)
                .put("created_at_ms", now)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("phase7_contract", Phase7Contract.contractJson())
                .put("phase8_contract", Phase8Contract.contractJson())
                .put("phase9_contract", Phase9Contract.contractJson())
                .put("phase10_contract", Phase10Contract.contractJson())
                .put("phase11_contract", Phase11Contract.contractJson())
                .put("phase13_validation_contract", profileVersion >= 4
                        ? Phase13ValidationContract.contractJson() : JSONObject.NULL)
                .put("profile", profile)
                .put("profile_sha256", profile.getString("profile_sha256"))
                .put("driver", driver.toJson())
                .put("comparison_mode", normalizedMode)
                .put("reference_driver", referenceDriver == null
                        ? JSONObject.NULL : referenceDriver.toJson())
                .put("preflight", preflight)
                .put("final_environment", JSONObject.NULL)
                .put("environment_comparison", JSONObject.NULL)
                .put("execution", new JSONObject()
                        .put("state", "pending")
                        .put("started_at_ms", JSONObject.NULL)
                        .put("finished_at_ms", JSONObject.NULL)
                        .put("pause_requested", false)
                        .put("recovery_count", 0)
                        .put("steps", states))
                .put("report", JSONObject.NULL)
                .put("diagnostic_bundle", JSONObject.NULL)
                .put("limitations", profile.optInt("profile_version", 1) >= 4
                        ? Phase13ValidationContract.LIMITATION
                        : profile.optInt("profile_version", 1) >= 3
                        ? Phase11Contract.LIMITATION
                        : profile.optInt("profile_version", 1) >= 2
                        ? Phase8Contract.LIMITATION : Phase7Contract.LIMITATION);
        File file = new File(directory, "qualification.json");
        save(file, manifest);
        ResultFiles.writeAtomic(new File(directory, "profile.json"), profile.toString(2));
        ResultFiles.writeAtomic(new File(directory, "preflight.json"), preflight.toString(2));
        return file;
    }

    static JSONObject load(File file) throws Exception {
        JSONObject manifest = new JSONObject(ResultFiles.readUtf8(file));
        if (!verify(manifest)) throw new IllegalArgumentException("qualification.json inválido");
        return manifest;
    }

    static void save(File file, JSONObject manifest) throws Exception {
        if (!verify(manifest)) throw new IllegalArgumentException("Manifesto Full inválido");
        ResultFiles.writeAtomic(file, manifest.toString(2));
    }

    static boolean verify(JSONObject manifest) {
        try {
            int profileVersionHint = manifest.optJSONObject("profile") == null ? -1
                    : manifest.optJSONObject("profile").optInt("profile_version", -1);
            int expectedSchema = profileVersionHint >= 3
                    ? Phase11Contract.QUALIFICATION_SCHEMA_VERSION
                    : Phase7Contract.QUALIFICATION_SCHEMA_VERSION;
            if (manifest.optInt("qualification_schema_version", -1) != expectedSchema) return false;
            if (!manifest.optString("qualification_id", "").matches("qualification-[0-9]{10,20}")) {
                return false;
            }
            JSONObject profile = manifest.optJSONObject("profile");
            if (profile == null || !QualificationProfile.verify(profile)) return false;
            if (!profile.optString("profile_sha256").equalsIgnoreCase(
                    manifest.optString("profile_sha256"))) return false;
            JSONObject driver = manifest.optJSONObject("driver");
            if (driver == null || driver.optString("sha256", "").length() != 64) return false;
            String comparisonMode = manifest.optString("comparison_mode", "system_vs_turnip");
            if (!"system_vs_turnip".equals(comparisonMode)
                    && !"turnip_vs_turnip".equals(comparisonMode)) return false;
            JSONObject referenceDriver = manifest.optJSONObject("reference_driver");
            if ("turnip_vs_turnip".equals(comparisonMode)) {
                if (referenceDriver == null
                        || referenceDriver.optString("sha256", "").length() != 64
                        || driver.optString("sha256").equalsIgnoreCase(
                                referenceDriver.optString("sha256"))) return false;
            }
            JSONObject execution = manifest.optJSONObject("execution");
            JSONArray states = execution == null ? null : execution.optJSONArray("steps");
            int profileVersion = profile.optInt("profile_version", -1);
            java.util.List<QualificationProfile.Step> expectedSteps =
                    QualificationProfile.stepsForVersion(profileVersion);
            if (states == null || states.length() != expectedSteps.size()) return false;
            for (QualificationProfile.Step step : expectedSteps) {
                JSONObject state = stateFor(manifest, step.stepId);
                if (state == null || !validStatus(state.optString("status"))) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static File findLatestIncomplete(File filesDir) {
        File root = new File(filesDir, "qualifications");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return null;
        Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
        for (File dir : dirs) {
            File file = new File(dir, "qualification.json");
            if (!file.isFile()) continue;
            try {
                JSONObject manifest = load(file);
                String state = manifest.getJSONObject("execution").optString("state");
                if (!state.startsWith("completed")) return file;
            } catch (Exception ignored) {
                // Invalid manifests are never resumed automatically.
            }
        }
        return null;
    }

    static int recoverInterrupted(JSONObject manifest) throws Exception {
        JSONArray states = manifest.getJSONObject("execution").getJSONArray("steps");
        int recovered = 0;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.getJSONObject(index);
            if (!"running".equals(state.optString("status"))) continue;
            state.put("status", "pending")
                    .put("started_at_ms", JSONObject.NULL)
                    .put("finished_at_ms", JSONObject.NULL)
                    .put("failure", JSONObject.NULL);
            recovered++;
        }
        if (recovered > 0) {
            JSONObject execution = manifest.getJSONObject("execution");
            execution.put("state", "paused")
                    .put("pause_requested", false)
                    .put("recovery_count", execution.optInt("recovery_count", 0) + recovered);
        }
        return recovered;
    }

    static JSONObject nextPending(JSONObject manifest) {
        JSONArray states = manifest.optJSONObject("execution") == null ? null
                : manifest.optJSONObject("execution").optJSONArray("steps");
        if (states == null) return null;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.optJSONObject(index);
            if (state != null && "pending".equals(state.optString("status"))) return state;
        }
        return null;
    }

    static JSONObject stateFor(JSONObject manifest, String stepId) {
        JSONArray states = manifest.optJSONObject("execution") == null ? null
                : manifest.optJSONObject("execution").optJSONArray("steps");
        if (states == null) return null;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.optJSONObject(index);
            if (state != null && stepId.equals(state.optString("step_id"))) return state;
        }
        return null;
    }

    static void markRunning(JSONObject manifest) throws Exception {
        JSONObject execution = manifest.getJSONObject("execution");
        if (execution.isNull("started_at_ms")) execution.put("started_at_ms", System.currentTimeMillis());
        execution.put("state", "running").put("pause_requested", false);
    }

    static void markStepRunning(JSONObject manifest, String stepId) throws Exception {
        JSONObject state = requireState(manifest, stepId);
        if (!"pending".equals(state.optString("status"))) {
            throw new IllegalStateException("Etapa não pendente: " + stepId);
        }
        state.put("status", "running")
                .put("attempt_count", state.optInt("attempt_count", 0) + 1)
                .put("started_at_ms", System.currentTimeMillis())
                .put("finished_at_ms", JSONObject.NULL)
                .put("failure", JSONObject.NULL);
    }

    static void markStepCompleted(File filesDir, JSONObject manifest, String stepId,
                                  File suiteFile, JSONObject report) throws Exception {
        JSONObject state = requireState(manifest, stepId);
        if (!"running".equals(state.optString("status"))) {
            throw new IllegalStateException("Etapa não está executando: " + stepId);
        }
        if (!ResultFiles.isInside(filesDir, suiteFile)) {
            throw new IllegalArgumentException("suite.json fora do armazenamento interno");
        }
        state.put("status", "completed")
                .put("finished_at_ms", System.currentTimeMillis())
                .put("suite_relative_path", relative(filesDir, suiteFile))
                .put("suite_id", report.optString("suite_id", suiteFile.getParentFile().getName()))
                .put("artifact_relative_path", relative(filesDir, suiteFile))
                .put("artifact_id", report.optString("suite_id", suiteFile.getParentFile().getName()))
                .put("result_type", "suite")
                .put("failure", JSONObject.NULL);
    }


    static void markStepCompletedArtifact(File filesDir, JSONObject manifest, String stepId,
                                          File artifactFile, JSONObject report,
                                          String resultType) throws Exception {
        JSONObject state = requireState(manifest, stepId);
        if (!"running".equals(state.optString("status"))) {
            throw new IllegalStateException("Etapa não está executando: " + stepId);
        }
        if (!ResultFiles.isInside(filesDir, artifactFile)) {
            throw new IllegalArgumentException("Resultado fora do armazenamento interno");
        }
        String id = report.optString("report_id", artifactFile.getParentFile().getName());
        state.put("status", "completed")
                .put("finished_at_ms", System.currentTimeMillis())
                .put("artifact_relative_path", relative(filesDir, artifactFile))
                .put("artifact_id", id)
                .put("result_type", resultType)
                .put("failure", JSONObject.NULL);
    }

    static void markStepFailed(JSONObject manifest, String stepId, String message) throws Exception {
        JSONObject state = requireState(manifest, stepId);
        state.put("status", "failed")
                .put("finished_at_ms", System.currentTimeMillis())
                .put("failure", new JSONObject()
                        .put("message", message == null ? "falha desconhecida" : message)
                        .put("recorded_at_ms", System.currentTimeMillis()));
    }

    static void requestPause(JSONObject manifest) throws Exception {
        manifest.getJSONObject("execution").put("pause_requested", true);
    }

    static boolean pauseRequested(JSONObject manifest) {
        JSONObject execution = manifest.optJSONObject("execution");
        return execution != null && execution.optBoolean("pause_requested", false);
    }

    static void markPaused(JSONObject manifest) throws Exception {
        manifest.getJSONObject("execution").put("state", "paused").put("pause_requested", false);
    }

    static void finish(JSONObject manifest, JSONObject finalEnvironment,
                       JSONObject environmentComparison, JSONObject report,
                       JSONObject bundleDescriptor) throws Exception {
        JSONObject execution = manifest.getJSONObject("execution");
        execution.put("state", countStatus(manifest, "failed") > 0
                        ? "completed_with_failures" : "completed")
                .put("finished_at_ms", System.currentTimeMillis())
                .put("pause_requested", false);
        manifest.put("final_environment", finalEnvironment)
                .put("environment_comparison", environmentComparison)
                .put("report", report)
                .put("diagnostic_bundle", bundleDescriptor);
    }

    static int countStatus(JSONObject manifest, String status) {
        JSONArray states = manifest.optJSONObject("execution") == null ? null
                : manifest.optJSONObject("execution").optJSONArray("steps");
        if (states == null) return 0;
        int count = 0;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.optJSONObject(index);
            if (state != null && status.equals(state.optString("status"))) count++;
        }
        return count;
    }

    static File suiteFile(File filesDir, JSONObject state) throws Exception {
        String path = state.optString("suite_relative_path", "");
        if (path.isEmpty()) path = state.optString("artifact_relative_path", "");
        if (path.isEmpty()) return null;
        File file = new File(filesDir, path);
        if (!ResultFiles.isInside(filesDir, file)) throw new IllegalArgumentException("Caminho inválido");
        return file;
    }

    private static JSONObject requireState(JSONObject manifest, String stepId) {
        JSONObject state = stateFor(manifest, stepId);
        if (state == null) throw new IllegalArgumentException("Etapa ausente: " + stepId);
        return state;
    }

    private static String relative(File filesDir, File file) throws Exception {
        String root = filesDir.getCanonicalPath();
        String child = file.getCanonicalPath();
        return child.substring(root.length() + 1).replace(File.separatorChar, '/');
    }

    private static boolean validStatus(String value) {
        return "pending".equals(value) || "running".equals(value)
                || "completed".equals(value) || "failed".equals(value)
                || "skipped".equals(value);
    }
}
