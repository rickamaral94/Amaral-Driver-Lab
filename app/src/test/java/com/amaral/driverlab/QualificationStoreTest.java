package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class QualificationStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void interruptedStepReturnsToPendingWithoutResult() throws Exception {
        File filesDir = temporary.newFolder("files");
        File driverDir = temporary.newFolder("driver");
        File zip = temporary.newFile("driver.zip");
        DriverPackage driver = new DriverPackage(Phase4TestData.sha('a'), "Driver A", "1",
                "Amaral", "1", 28, "libvulkan_freedreno.so", driverDir, zip,
                new JSONObject());
        File file = QualificationStore.create(filesDir, driver, preflight());
        JSONObject manifest = QualificationStore.load(file);
        QualificationStore.markRunning(manifest);
        QualificationStore.markStepRunning(manifest, "correctness_pre");
        QualificationStore.save(file, manifest);

        JSONObject reloaded = QualificationStore.load(file);
        assertEquals(1, QualificationStore.recoverInterrupted(reloaded));
        JSONObject state = QualificationStore.stateFor(reloaded, "correctness_pre");
        assertEquals("pending", state.getString("status"));
        assertEquals(1, state.getInt("attempt_count"));
        assertTrue(state.isNull("suite_id"));
        assertEquals(1, reloaded.getJSONObject("execution").getInt("recovery_count"));
    }

    @Test
    public void failedRunnerStepKeepsItsSuiteArtifact() throws Exception {
        File filesDir = temporary.newFolder("failed-files");
        File driverDir = temporary.newFolder("failed-driver");
        File zip = temporary.newFile("failed-driver.zip");
        DriverPackage driver = new DriverPackage(Phase4TestData.sha('b'), "Driver B", "1",
                "Amaral", "1", 28, "libvulkan_freedreno.so", driverDir, zip,
                new JSONObject());
        File file = QualificationStore.create(filesDir, driver, preflight());
        JSONObject manifest = QualificationStore.load(file);
        QualificationStore.markRunning(manifest);
        QualificationStore.markStepRunning(manifest, "correctness_pre");
        File suiteDirectory = new File(new File(filesDir, "runs"), "suite-123");
        assertTrue(suiteDirectory.mkdirs());
        File suite = new File(suiteDirectory, "suite.json");
        ResultFiles.writeAtomic(suite, "{}");

        QualificationStore.markStepFailedArtifact(filesDir, manifest, "correctness_pre",
                suite, new JSONObject().put("suite_id", "suite-123"), "runner crash");

        JSONObject state = QualificationStore.stateFor(manifest, "correctness_pre");
        assertEquals("failed", state.getString("status"));
        assertEquals("runs/suite-123/suite.json", state.getString("suite_relative_path"));
        assertEquals("suite-123", state.getString("suite_id"));
        assertEquals("runner crash", state.getJSONObject("failure").getString("message"));
    }

    private static JSONObject preflight() throws Exception {
        return new JSONObject()
                .put("device", new JSONObject())
                .put("evaluation", new JSONObject()
                        .put("warnings", new org.json.JSONArray())
                        .put("blockers", new org.json.JSONArray())
                        .put("eligible_to_start", true)
                        .put("ranking_blocked", false));
    }
}
