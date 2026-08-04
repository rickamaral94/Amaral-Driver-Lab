package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DeepDiagnosticsBundle {
    private static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;

    private DeepDiagnosticsBundle() {}

    static JSONObject create(File directory, JSONObject report) throws Exception {
        File bundle = new File(directory, "phase10-diagnostic-bundle.zip");
        List<File> files = new ArrayList<>();
        File[] listed = directory.listFiles(file -> file.isFile()
                && !file.equals(bundle) && file.length() <= MAX_ENTRY_BYTES);
        if (listed != null) {
            java.util.Collections.addAll(files, listed);
            files.sort(Comparator.comparing(File::getName));
        }
        JSONArray entries = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bundle, false))) {
            for (File file : files) {
                byte[] bytes = read(file);
                ZipEntry entry = new ZipEntry(file.getName());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
                entries.put(new JSONObject()
                        .put("path", file.getName())
                        .put("bytes", bytes.length)
                        .put("sha256", ResultFiles.sha256(file)));
            }
            JSONObject manifest = new JSONObject()
                    .put("deep_diagnostic_bundle_version", Phase10Contract.BUNDLE_VERSION)
                    .put("report_id", report.getString("report_id"))
                    .put("profile_sha256", report.getString("profile_sha256"))
                    .put("entry_count", entries.length())
                    .put("entries", entries)
                    .put("includes_global_logcat", false)
                    .put("adb_capture_recommended", true)
                    .put("limitations", "Logcat global, tombstones e dumps do kernel exigem ADB/root.");
            byte[] manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
            ZipEntry entry = new ZipEntry("manifest.json");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(manifestBytes);
            zip.closeEntry();
        }
        return new JSONObject()
                .put("deep_diagnostic_bundle_version", Phase10Contract.BUNDLE_VERSION)
                .put("relative_path", bundle.getName())
                .put("bytes", bundle.length())
                .put("sha256", ResultFiles.sha256(bundle))
                .put("entry_count", entries.length() + 1)
                .put("includes_global_logcat", false)
                .put("adb_capture_recommended", true);
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
}
