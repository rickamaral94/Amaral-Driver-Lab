package com.amaral.driverlab;

import android.content.Context;

import com.amaral.driverlab.telemetry.TelemetryContract;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class TelemetryImporter {
    static final String ROOT_DIRECTORY = "telemetry_sessions";
    static final String BUNDLE_FILE = "telemetry_bundle.json";
    static final String REPORT_FILE = "telemetry_report.json";

    private TelemetryImporter() {}

    static ImportResult importBundle(Context context, InputStream input,
                                     DriverPackage expectedDriver) throws Exception {
        JSONObject bundle = readBundle(input, Phase7Contract.MAX_IMPORT_BYTES);
        TelemetryContract.validateBundle(bundle);
        JSONObject verification = verifyDriverBinding(bundle, expectedDriver);
        String sourceSha = JsonCanonicalizer.sha256(bundle);
        JSONObject session = bundle.getJSONObject("session");
        String sessionId = session.getString("session_id");

        File root = new File(context.getFilesDir(), ROOT_DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("não foi possível criar armazenamento de telemetria");
        }
        File target = new File(root, sessionId);
        if (!ResultFiles.isInside(root, target)) throw new SecurityException("session_id fora do diretório");
        if (target.exists()) {
            File existingReportFile = new File(target, REPORT_FILE);
            if (!existingReportFile.isFile()) {
                throw new IllegalStateException("sessão existente está incompleta");
            }
            JSONObject existing = new JSONObject(ResultFiles.readUtf8(existingReportFile));
            if (!sourceSha.equals(existing.optString("source_bundle_sha256"))) {
                throw new IllegalArgumentException("session_id já existe com conteúdo diferente");
            }
            return new ImportResult(target, existingReportFile, existing, true);
        }

        File partial = new File(root, ".partial-" + sessionId + "-" + UUID.randomUUID());
        if (!partial.mkdirs()) throw new IllegalStateException("não foi possível iniciar a importação");
        try {
            File bundleFile = new File(partial, BUNDLE_FILE);
            ResultFiles.writeAtomic(bundleFile, bundle.toString(2));
            JSONObject report = buildReport(context, bundle, sourceSha, verification);
            File reportFile = new File(partial, REPORT_FILE);
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            if (!partial.renameTo(target)) throw new IllegalStateException("falha ao publicar a sessão");
            return new ImportResult(target, new File(target, REPORT_FILE), report, false);
        } catch (Throwable error) {
            deleteRecursively(partial);
            throw error;
        }
    }

    static JSONObject readBundle(InputStream input, int maximumBytes) throws Exception {
        if (input == null) throw new IllegalArgumentException("arquivo indisponível");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > maximumBytes) throw new IllegalArgumentException("arquivo excede 32 MiB");
            output.write(buffer, 0, count);
        }
        if (total == 0) throw new IllegalArgumentException("arquivo vazio");
        return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
    }

    static JSONObject verifyDriverBinding(JSONObject bundle, DriverPackage expectedDriver)
            throws Exception {
        JSONObject declared = bundle.getJSONObject("session").getJSONObject("driver_binding");
        String declaredMode = declared.getString("mode");
        JSONObject result = new JSONObject()
                .put("verification_version", 1)
                .put("declared_mode", declaredMode)
                .put("selected_local_mode", expectedDriver == null ? "system" : "custom")
                .put("selected_package_present", expectedDriver != null)
                .put("hash_match", JSONObject.NULL)
                .put("verified", false)
                .put("verification_scope",
                        "Confirma somente que a declaração do produtor coincide com a seleção local; "
                                + "não prova que o emulador carregou esse driver durante toda a sessão.");
        if (expectedDriver == null) {
            if (!TelemetryContract.DRIVER_SYSTEM.equals(declaredMode)) {
                throw new IllegalArgumentException(
                        "o arquivo declara driver custom, mas a validação selecionou sistema");
            }
            return result.put("verified", true);
        }
        if (!TelemetryContract.DRIVER_CUSTOM.equals(declaredMode)) {
            throw new IllegalArgumentException(
                    "o arquivo declara driver do sistema, mas foi selecionado um pacote custom");
        }
        String declaredHash = declared.getString("candidate_sha256");
        boolean matches = expectedDriver.sha256.equalsIgnoreCase(declaredHash);
        result.put("declared_candidate_sha256", declaredHash.toLowerCase())
                .put("selected_candidate_sha256", expectedDriver.sha256.toLowerCase())
                .put("hash_match", matches)
                .put("verified", matches);
        if (!matches) throw new IllegalArgumentException("hash do driver declarado não coincide");
        return result;
    }

    private static JSONObject buildReport(Context context, JSONObject bundle, String sourceSha,
                                          JSONObject verification) throws Exception {
        JSONObject session = new JSONObject(bundle.getJSONObject("session").toString());
        return new JSONObject()
                .put("telemetry_report_schema_version",
                        Phase7Contract.TELEMETRY_REPORT_SCHEMA_VERSION)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("imported_at_unix_ms", System.currentTimeMillis())
                .put("session_id", session.getString("session_id"))
                .put("source_bundle_sha256", sourceSha)
                .put("source_bundle_relative_path", BUNDLE_FILE)
                .put("raw_events_embedded", false)
                .put("session", session)
                .put("local_driver_verification", verification)
                .put("analysis", TelemetryAnalysis.analyze(bundle))
                .put("import_host_device", DeviceSnapshot.capture(context))
                .put("phase7_contract", Phase7Contract.contractJson())
                .put("automatic_upload", false);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    static final class ImportResult {
        final File directory;
        final File reportFile;
        final JSONObject report;
        final boolean alreadyImported;

        ImportResult(File directory, File reportFile, JSONObject report, boolean alreadyImported) {
            this.directory = directory;
            this.reportFile = reportFile;
            this.report = report;
            this.alreadyImported = alreadyImported;
        }
    }
}
