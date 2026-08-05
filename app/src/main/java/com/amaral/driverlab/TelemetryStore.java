package com.amaral.driverlab;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class TelemetryStore {
    private static final String ROOT = "telemetry-sessions";
    private static final String SESSION_FILE = "session.json";
    private static final String SUMMARY_FILE = "summary.json";
    private static final String LINK_FILE = "suite-link.json";

    private TelemetryStore() {}

    static TelemetrySessionRecord importSession(File filesDir, InputStream input) throws Exception {
        byte[] bytes = readBounded(input, Phase9Contract.MAX_IMPORT_BYTES);
        JSONObject session = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        TelemetrySessionRecord parsed = TelemetrySessionRecord.parse(null, session);
        File root = new File(filesDir, ROOT);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o diretório de telemetria");
        }
        File directory = new File(root, parsed.sessionId);
        File sessionFile = new File(directory, SESSION_FILE);
        if (directory.exists()) {
            if (!sessionFile.isFile()) {
                throw new IllegalArgumentException("session_id colide com diretório incompleto");
            }
            TelemetrySessionRecord existing = load(sessionFile);
            if (!existing.sessionSha256.equals(parsed.sessionSha256)) {
                throw new IllegalArgumentException("session_id já existe com conteúdo diferente");
            }
            return existing;
        }
        File partial = new File(root, ".partial-" + parsed.sessionId + "-"
                + System.nanoTime());
        if (!partial.mkdirs()) throw new IllegalStateException("Falha ao criar importação parcial");
        try {
            ResultFiles.writeAtomic(new File(partial, SESSION_FILE), session.toString(2));
            ResultFiles.writeAtomic(new File(partial, SUMMARY_FILE), parsed.summary.toString(2));
            if (!partial.renameTo(directory)) {
                throw new IllegalStateException("Falha ao publicar sessão importada");
            }
        } catch (Exception error) {
            deleteTree(partial);
            throw error;
        }
        return load(new File(directory, SESSION_FILE));
    }

    static TelemetrySessionRecord load(File sessionFile) throws Exception {
        JSONObject session = new JSONObject(ResultFiles.readUtf8(sessionFile));
        return TelemetrySessionRecord.parse(sessionFile, session);
    }

    static List<TelemetrySessionRecord> scan(File filesDir) {
        List<TelemetrySessionRecord> output = new ArrayList<>();
        File root = new File(filesDir, ROOT);
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) return output;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            if (directory.getName().startsWith(".partial-")) continue;
            File file = new File(directory, SESSION_FILE);
            if (!file.isFile()) continue;
            try {
                output.add(load(file));
            } catch (Exception ignored) {
                // Invalid sessions stay on disk but never enter comparisons.
            }
        }
        output.sort(Comparator.comparingLong((TelemetrySessionRecord item) -> item.finishedAtMs)
                .reversed());
        return output;
    }

    static JSONObject linkToSuite(File filesDir, TelemetrySessionRecord session,
                                  SuiteRecord suite) throws Exception {
        if (session == null || suite == null) throw new IllegalArgumentException("Seleção ausente");
        if (!session.hardwarePublicKey.equals(suite.publicHardwareKey)) {
            throw new IllegalArgumentException("hardware_public_key não corresponde à suíte");
        }
        if ("custom".equals(session.driverMode)
                && !session.driverSha256.equalsIgnoreCase(suite.candidateSha256)) {
            throw new IllegalArgumentException("SHA-256 do driver não corresponde à suíte");
        }
        String suiteSha = JsonCanonicalizer.sha256(suite.report);
        JSONObject link = new JSONObject()
                .put("telemetry_link_schema_version", Phase9Contract.TELEMETRY_LINK_VERSION)
                .put("linked_at_ms", System.currentTimeMillis())
                .put("session_id", session.sessionId)
                .put("session_sha256", session.sessionSha256)
                .put("suite_id", suite.suiteId)
                .put("suite_sha256", suiteSha)
                .put("suite_relative_path", relativePath(filesDir, suite.file))
                .put("driver_arm", session.driverMode)
                .put("hardware_public_key", session.hardwarePublicKey)
                .put("source_session_mutated", false)
                .put("suite_mutated", false)
                .put("phase9_contract", Phase9Contract.contractJson())
                .put("phase10_contract", Phase10Contract.contractJson());
        File directory = session.file == null ? null : session.file.getParentFile();
        if (directory == null || !ResultFiles.isInside(new File(filesDir, ROOT), directory)) {
            throw new IllegalArgumentException("sessão fora do armazenamento local");
        }
        ResultFiles.writeAtomic(new File(directory, LINK_FILE), link.toString(2));
        return link;
    }

    static JSONObject readLink(TelemetrySessionRecord session) {
        try {
            if (session == null || session.file == null) return null;
            File file = new File(session.file.getParentFile(), LINK_FILE);
            if (!file.isFile()) return null;
            JSONObject link = new JSONObject(ResultFiles.readUtf8(file));
            if (link.optInt("telemetry_link_schema_version", -1)
                    != Phase9Contract.TELEMETRY_LINK_VERSION
                    || !session.sessionId.equals(link.optString("session_id"))
                    || !session.sessionSha256.equalsIgnoreCase(
                            link.optString("session_sha256"))) {
                return null;
            }
            return link;
        } catch (Exception ignored) {
            return null;
        }
    }

    static JSONObject exportEnvelope(TelemetrySessionRecord session) throws Exception {
        JSONObject link = readLink(session);
        return new JSONObject()
                .put("telemetry_export_version", 1)
                .put("exported_at_ms", System.currentTimeMillis())
                .put("phase9_contract", Phase9Contract.contractJson())
                .put("phase10_contract", Phase10Contract.contractJson())
                .put("session_sha256", session.sessionSha256)
                .put("session", new JSONObject(session.session.toString()))
                .put("summary", new JSONObject(session.summary.toString()))
                .put("suite_link", link == null ? JSONObject.NULL : link)
                .put("automatic_upload", false)
                .put("limitations", Phase9Contract.LIMITATION);
    }

    static void writeTo(File target, JSONObject value) throws Exception {
        ResultFiles.writeAtomic(target, value.toString(2));
    }

    private static byte[] readBounded(InputStream input, long maximum) throws Exception {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int count;
            while ((count = source.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) {
                    throw new IllegalArgumentException("JSON excede " + maximum + " bytes");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String relativePath(File filesDir, File file) throws Exception {
        if (file == null || !ResultFiles.isInside(filesDir, file)) {
            throw new IllegalArgumentException("suite.json fora do armazenamento local");
        }
        String root = filesDir.getCanonicalPath() + File.separator;
        return file.getCanonicalPath().substring(root.length()).replace(File.separatorChar, '/');
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
