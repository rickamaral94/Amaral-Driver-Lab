package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DiagnosticBundle {
    private static final long MAX_SOURCE_FILE_BYTES = 64L * 1024L * 1024L;

    private DiagnosticBundle() {}

    static JSONObject create(File filesDir, File qualificationFile,
                             JSONObject manifest, JSONObject report) throws Exception {
        File directory = qualificationFile.getParentFile();
        File bundle = new File(directory, "diagnostic-bundle.zip");
        List<EntrySource> sources = new ArrayList<>();
        addFile(sources, new File(directory, "profile.json"), "profile.json");
        addFile(sources, new File(directory, "preflight.json"), "preflight.json");
        addFile(sources, new File(directory, "final-environment.json"), "final-environment.json");
        addFile(sources, new File(directory, "environment-comparison.json"), "environment-comparison.json");
        addFile(sources, new File(directory, "report.json"), "report.json");
        addFile(sources, new File(directory, "summary.html"), "summary.html");

        JSONArray states = manifest.getJSONObject("execution").getJSONArray("steps");
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.getJSONObject(index);
            if (!"completed".equals(state.optString("status"))) continue;
            File suite = QualificationStore.suiteFile(filesDir, state);
            if (suite == null || !suite.isFile()) continue;
            collectDirectory(sources, suite.getParentFile(),
                    "results/" + state.getString("step_id"));
        }
        sources.sort(Comparator.comparing(item -> item.path));
        JSONArray manifestEntries = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bundle, false))) {
            for (EntrySource source : sources) {
                byte[] bytes = read(source.file);
                String sha = sha256(bytes);
                ZipEntry entry = new ZipEntry(source.path);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
                manifestEntries.put(new JSONObject()
                        .put("path", source.path)
                        .put("bytes", bytes.length)
                        .put("sha256", sha));
            }
            JSONObject bundleManifest = new JSONObject()
                    .put("diagnostic_bundle_version", Phase7Contract.BUNDLE_VERSION)
                    .put("qualification_id", manifest.getString("qualification_id"))
                    .put("profile_sha256", manifest.getString("profile_sha256"))
                    .put("driver_sha256", manifest.getJSONObject("driver").getString("sha256"))
                    .put("entry_count", manifestEntries.length())
                    .put("entries", manifestEntries)
                    .put("limitations", "Logcat global e tombstones do sistema exigem captura externa via ADB.");
            byte[] manifestBytes = bundleManifest.toString(2).getBytes(StandardCharsets.UTF_8);
            ZipEntry entry = new ZipEntry("manifest.json");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(manifestBytes);
            zip.closeEntry();
        }
        return new JSONObject()
                .put("diagnostic_bundle_version", Phase7Contract.BUNDLE_VERSION)
                .put("relative_path", bundle.getName())
                .put("bytes", bundle.length())
                .put("sha256", sha256(read(bundle)))
                .put("entry_count", manifestEntries.length() + 1)
                .put("includes_global_logcat", false)
                .put("adb_capture_recommended", true);
    }

    private static void collectDirectory(List<EntrySource> output, File directory,
                                         String prefix) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectDirectory(output, file, prefix + "/" + file.getName());
            else if (file.isFile() && file.length() <= MAX_SOURCE_FILE_BYTES) {
                output.add(new EntrySource(file, prefix + "/" + file.getName()));
            }
        }
    }

    private static void addFile(List<EntrySource> output, File file, String path) {
        if (file.isFile() && file.length() <= MAX_SOURCE_FILE_BYTES) output.add(new EntrySource(file, path));
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder output = new StringBuilder();
        for (byte item : digest) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static final class EntrySource {
        final File file;
        final String path;
        EntrySource(File file, String path) {
            this.file = file;
            this.path = path;
        }
    }
}
