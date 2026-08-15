package com.amaral.driverlab;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppDiagnosticsContractTest {
    @Test
    public void diagnosticsUseAppSpecificAndroidDataAndOfferZipExport() throws Exception {
        String diagnostics = read(source("main/java/com/amaral/driverlab/AppDiagnostics.java"));
        String application = read(source(
                "main/java/com/amaral/driverlab/DriverLabApplication.java"));
        String main = read(source("main/java/com/amaral/driverlab/MainActivity.java"));
        String manifest = read(source("main/AndroidManifest.xml"));

        assertTrue(diagnostics.contains("getExternalFilesDir(null)"));
        assertTrue(diagnostics.contains("new File(root, \"logs\")"));
        assertTrue(diagnostics.contains("app-main.log"));
        assertTrue(diagnostics.contains("app-runner.log"));
        assertTrue(diagnostics.contains("ZipOutputStream"));
        assertTrue(application.contains("AppDiagnostics.initialize(this)"));
        assertTrue(main.contains("EXPORTAR LOGS DO APP (.ZIP)"));
        assertTrue(main.contains("Intent.ACTION_CREATE_DOCUMENT"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"));
    }

    @Test
    public void homeHeaderOffersLogExportNextToLanguageSelector() throws Exception {
        String main = read(source("main/java/com/amaral/driverlab/MainActivity.java"));

        int logsButton = main.indexOf(
                "logText(\"Exportar logs do ADL\", \"Export ADL logs\")");
        int languageButton = main.indexOf(
                "getString(R.string.language_selector_content_description)");
        assertTrue(logsButton >= 0);
        assertTrue(languageButton > logsButton);
        assertTrue(main.contains("view -> chooseAppDiagnosticsExport()"));
    }

    @Test
    public void androidExitHistoryAndNativeVisualBreadcrumbsArePersisted() throws Exception {
        String exits = read(source("main/java/com/amaral/driverlab/ExitHistoryApi30.java"));
        String runnerState = read(source(
                "main/java/com/amaral/driverlab/RunnerProcessState.java"));
        String nativeSource = read(source("main/cpp/visual_scenes.cpp"));

        assertTrue(exits.contains("getHistoricalProcessExitReasons"));
        assertTrue(exits.contains("REASON_CRASH_NATIVE"));
        assertTrue(exits.contains("getTraceInputStream"));
        assertTrue(runnerState.contains("runner_checkpoint"));
        assertTrue(nativeSource.contains("native_visual_image_acquired"));
        assertTrue(nativeSource.contains("native_visual_queue_submitted"));
        assertTrue(nativeSource.contains("native_visual_present_returned"));
        assertTrue(nativeSource.contains("native_visual_checkpoint_written"));
        assertTrue(nativeSource.contains("fsync(descriptor)"));
    }

    @Test
    public void fullLogRotatesAndKeepsFirstArchive() throws Exception {
        File directory = Files.createTempDirectory("driverlab-log-rotation").toFile();
        File log = new File(directory, "app-runner.log");
        try {
            try (RandomAccessFile output = new RandomAccessFile(log, "rw")) {
                output.setLength(AppDiagnostics.MAX_LOG_BYTES);
            }
            AppDiagnostics.rotateIfNeeded(log, 1L);
            assertFalse(log.exists());
            assertTrue(new File(directory, "app-runner.log.1").isFile());
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    private static File source(String relative) {
        File direct = new File("src/" + relative);
        return direct.isFile() ? direct : new File("app/src/" + relative);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
