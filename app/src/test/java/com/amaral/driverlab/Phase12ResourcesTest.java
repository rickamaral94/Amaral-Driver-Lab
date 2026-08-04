package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Phase12ResourcesTest {
    private static final String[] DIRECTORIES = {
            "values", "values-pt-rBR", "values-es", "values-fr",
            "values-de", "values-it", "values-ja", "values-zh-rCN"
    };

    @Test
    public void everySupportedLocaleHasCompleteLegacyArray() throws Exception {
        File res = resourceDirectory();
        String source = read(new File(res, "values/legacy_ui_source.xml"));
        int expected = itemCount(source);
        assertTrue(expected > 100);
        for (String directory : DIRECTORIES) {
            File strings = new File(res, directory + "/strings.xml");
            assertTrue("Missing " + strings, strings.isFile());
            String xml = read(strings);
            assertEquals(directory, expected, itemCount(extractArray(xml)));
            assertTrue(xml.contains("name=\"language_selector_title\""));
            assertTrue(xml.contains("name=\"report_results_by_area\""));
        }
    }

    @Test
    public void localizedResourcesDoNotRenameTechnicalIdentifiers() throws Exception {
        File res = resourceDirectory();
        String[] identifiers = {
                "VK_ERROR_DEVICE_LOST", "p99_gpu_frame_ms", "driver_sha256",
                "visual_scene_geometry/v1"
        };
        for (String directory : DIRECTORIES) {
            String xml = read(new File(res, directory + "/strings.xml"));
            for (String identifier : identifiers) {
                assertFalse(directory + " translated technical ID " + identifier,
                        xml.contains(identifier));
            }
        }
    }

    private static File resourceDirectory() {
        File direct = new File("src/main/res");
        return direct.isDirectory() ? direct : new File("app/src/main/res");
    }

    private static String read(File file) throws Exception {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String extractArray(String xml) {
        Matcher matcher = Pattern.compile(
                "<string-array name=\\\"legacy_ui_translation\\\">(.*?)</string-array>",
                Pattern.DOTALL).matcher(xml);
        assertTrue("legacy_ui_translation missing", matcher.find());
        return matcher.group(1);
    }

    private static int itemCount(String xml) {
        Matcher matcher = Pattern.compile("<item>").matcher(xml);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }
}
