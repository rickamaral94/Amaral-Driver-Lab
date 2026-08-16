package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

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
        addFile(sources, qualificationFile, "qualification.json");
        addFile(sources, new File(directory, "profile.json"), "profile.json");
        addFile(sources, new File(directory, "preflight.json"), "preflight.json");
        addFile(sources, new File(directory, "final-environment.json"), "final-environment.json");
        addFile(sources, new File(directory, "environment-comparison.json"), "environment-comparison.json");
        addFile(sources, new File(directory, "report.json"), "report.json");
        addFile(sources, new File(directory, "summary.html"), "summary.html");

        JSONArray states = manifest.getJSONObject("execution").getJSONArray("steps");
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.getJSONObject(index);
            String status = state.optString("status");
            if (!"completed".equals(status) && !"failed".equals(status)) continue;
            File suite = QualificationStore.suiteFile(filesDir, state);
            if (suite == null || !suite.isFile()) continue;
            collectDirectory(sources, suite.getParentFile(),
                    "results/" + state.getString("step_id"));
        }
        sources.sort(Comparator.comparing(item -> item.path));
        JSONArray manifestEntries = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bundle, false))) {
            for (EntrySource source : sources) {
                ZipEntry entry = new ZipEntry(source.path);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                CopiedEntry copied = copyAndDigest(source.file, zip);
                zip.closeEntry();
                manifestEntries.put(new JSONObject()
                        .put("path", source.path)
                        .put("bytes", copied.bytes)
                        .put("sha256", copied.sha256));
            }
            int profileVersion = manifest.getJSONObject("profile").optInt("profile_version", 1);
            int bundleVersion = profileVersion >= 3 ? Phase11Contract.BUNDLE_VERSION
                    : Phase7Contract.BUNDLE_VERSION;
            JSONObject bundleManifest = new JSONObject()
                    .put("diagnostic_bundle_version", bundleVersion)
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
        int profileVersion = manifest.getJSONObject("profile").optInt("profile_version", 1);
        int bundleVersion = profileVersion >= 3 ? Phase11Contract.BUNDLE_VERSION
                : Phase7Contract.BUNDLE_VERSION;
        return new JSONObject()
                .put("diagnostic_bundle_version", bundleVersion)
                .put("relative_path", bundle.getName())
                .put("bytes", bundle.length())
                .put("sha256", sha256(bundle))
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

    private static CopiedEntry copyAndDigest(File file, ZipOutputStream output) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                total += count;
            }
        }
        return new CopiedEntry(total, hex(digest.digest()));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] digest) {
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

    private static final class CopiedEntry {
        final long bytes;
        final String sha256;

        CopiedEntry(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }
}
