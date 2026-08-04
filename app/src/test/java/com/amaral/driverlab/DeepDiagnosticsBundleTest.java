package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class DeepDiagnosticsBundleTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bundleContainsReportPhasesAndManifest() throws Exception {
        File directory = temporary.newFolder("phase10");
        JSONObject report = new JSONObject()
                .put("report_id", "phase10-1")
                .put("profile_sha256", Phase10Contract.profileSha256());
        ResultFiles.writeAtomic(new File(directory, "report.json"), report.toString(2));
        ResultFiles.writeAtomic(new File(directory, "phase-01-system.json"), "{}");
        ResultFiles.writeAtomic(new File(directory, "phase-02-candidate.json"), "{}");
        JSONObject descriptor = DeepDiagnosticsBundle.create(directory, report);
        File bundle = new File(directory, descriptor.getString("relative_path"));
        assertTrue(bundle.isFile());
        assertEquals(64, descriptor.getString("sha256").length());
        try (ZipFile zip = new ZipFile(bundle)) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("report.json"));
            assertNotNull(zip.getEntry("phase-01-system.json"));
            assertNotNull(zip.getEntry("phase-02-candidate.json"));
        }
    }
}
