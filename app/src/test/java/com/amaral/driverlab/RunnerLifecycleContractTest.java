package com.amaral.driverlab;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RunnerLifecycleContractTest {
    private static final String[] RUNNERS = {
            "RunnerActivity.java",
            "VisualRunnerActivity.java",
            "DeepDiagnosticsRunnerActivity.java"
    };

    @Test
    public void isolatedRunnersNeverRemoveTheApplicationTask() throws Exception {
        File source = sourceDirectory();
        for (String runner : RUNNERS) {
            String java = read(new File(source, runner));
            assertFalse(runner + " must not remove the parent task",
                    java.contains("finishAndRemoveTask("));
            assertTrue(runner + " must finish only its own Activity",
                    java.contains("finish();"));
            assertTrue(runner + " must retire only after Activity destruction",
                    java.contains("RunnerProcessLifecycle.retireAfterActivityDestroyed()"));
            assertFalse(runner + " must not kill its process from the finish callback",
                    java.contains("Process.killProcess("));
        }
    }

    @Test
    public void coordinatorsPersistCrashEvidenceBeforeContinuing() throws Exception {
        File source = sourceDirectory();
        String runCoordinator = read(new File(source, "RunCoordinator.java"));
        String diagnosticsCoordinator = read(
                new File(source, "DeepDiagnosticsCoordinator.java"));
        assertTrue(runCoordinator.contains("RunnerProcessState.attachToSyntheticFailure"));
        assertTrue(runCoordinator.contains("finishCalibrationFailure"));
        assertTrue(runCoordinator.contains("\"calibration_failed\""));
        assertTrue(diagnosticsCoordinator.contains(
                "RunnerProcessState.attachToSyntheticFailure"));
        assertTrue(diagnosticsCoordinator.contains("runnerExitedUnexpectedly"));
    }

    private static File sourceDirectory() {
        File direct = new File("src/main/java/com/amaral/driverlab");
        return direct.isDirectory() ? direct
                : new File("app/src/main/java/com/amaral/driverlab");
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
