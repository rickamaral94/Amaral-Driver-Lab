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

    @Test
    public void runnerActivitiesUseASeparateDisposableTask() throws Exception {
        File source = sourceDirectory();
        String isolation = read(new File(source, "RunnerTaskIsolation.java"));
        assertTrue(isolation.contains("Intent.FLAG_ACTIVITY_NEW_TASK"));
        assertTrue(isolation.contains("Intent.FLAG_ACTIVITY_CLEAR_TASK"));
        assertTrue(isolation.contains("Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS"));

        File manifest = new File(source.getParentFile().getParentFile().getParentFile()
                .getParentFile(),
                "AndroidManifest.xml");
        String xml = read(manifest);
        assertTrue(count(xml, "android:taskAffinity=\"${applicationId}.runner\"") == 3);
    }

    @Test
    public void destroyedQualificationCannotLaunchAnotherRunner() throws Exception {
        File source = sourceDirectory();
        String activity = read(new File(source, "QualificationActivity.java"));
        String coordinator = read(new File(source, "QualificationCoordinator.java"));
        String runCoordinator = read(new File(source, "RunCoordinator.java"));
        assertTrue(activity.contains("coordinator.stop()"));
        assertTrue(coordinator.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(coordinator.contains("currentRun.cancel()"));
        assertTrue(runCoordinator.contains("qualification_abort_recommended"));
        assertTrue(coordinator.contains("paused_after_runner_failure"));
    }

    private static File sourceDirectory() {
        File direct = new File("src/main/java/com/amaral/driverlab");
        return direct.isDirectory() ? direct
                : new File("app/src/main/java/com/amaral/driverlab");
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
