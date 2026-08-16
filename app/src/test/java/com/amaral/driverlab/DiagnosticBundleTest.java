package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class DiagnosticBundleTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bundleContainsManifestReportAndProfileAndStreamsLargeSource() throws Exception {
        File filesDir = temporary.newFolder("files");
        File driverDir = temporary.newFolder("driver");
        File zip = temporary.newFile("driver.zip");
        DriverPackage driver = new DriverPackage(Phase4TestData.sha('a'), "Driver A", "1",
                "Amaral", "1", 28, "libvulkan_freedreno.so", driverDir, zip,
                new JSONObject());
        File qualificationFile = QualificationStore.create(filesDir, driver, preflight());
        JSONObject manifest = QualificationStore.load(qualificationFile);
        File directory = qualificationFile.getParentFile();
        ResultFiles.writeAtomic(new File(directory, "final-environment.json"), "{}");
        ResultFiles.writeAtomic(new File(directory, "environment-comparison.json"), "{}");
        JSONObject report = new JSONObject()
                .put("qualification_id", manifest.getString("qualification_id"))
                .put("profile_sha256", manifest.getString("profile_sha256"));
        ResultFiles.writeAtomic(new File(directory, "report.json"), report.toString(2));
        File summary = new File(directory, "summary.html");
        byte[] block = new byte[64 * 1024];
        try (FileOutputStream output = new FileOutputStream(summary)) {
            for (int index = 0; index < 64; index++) output.write(block);
        }

        JSONObject descriptor = DiagnosticBundle.create(filesDir, qualificationFile,
                manifest, report);
        File bundle = new File(directory, descriptor.getString("relative_path"));
        assertTrue(bundle.isFile());
        assertEquals(64, descriptor.getString("sha256").length());
        try (ZipFile archive = new ZipFile(bundle)) {
            assertNotNull(archive.getEntry("manifest.json"));
            assertNotNull(archive.getEntry("report.json"));
            assertNotNull(archive.getEntry("profile.json"));
            assertEquals(summary.length(), archive.getEntry("summary.html").getSize());
        }
    }

    private static JSONObject preflight() throws Exception {
        return new JSONObject()
                .put("device", new JSONObject())
                .put("evaluation", new JSONObject()
                        .put("warnings", new JSONArray())
                        .put("blockers", new JSONArray())
                        .put("eligible_to_start", true)
                        .put("ranking_blocked", false));
    }
}
