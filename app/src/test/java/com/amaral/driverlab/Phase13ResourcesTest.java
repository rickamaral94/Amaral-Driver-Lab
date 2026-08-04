package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Phase13ResourcesTest {
    private static final String[] DIRECTORIES = {
            "values", "values-pt-rBR", "values-es", "values-fr",
            "values-de", "values-it", "values-ja", "values-zh-rCN"
    };

    @Test
    public void everyLocaleContainsTheCompletePhase13Surface() throws Exception {
        File res = resourceDirectory();
        Set<String> expected = phase13Keys(read(new File(res, "values/strings.xml")));
        assertTrue(expected.size() >= 120);
        for (String directory : DIRECTORIES) {
            File file = new File(res, directory + "/strings.xml");
            assertTrue("Missing " + file, file.isFile());
            assertEquals(directory, expected, phase13Keys(read(file)));
        }
    }

    @Test
    public void cjkResourcesRemainUtf8AndTechnicalIdsStayOutOfTranslations() throws Exception {
        File res = resourceDirectory();
        String japanese = read(new File(res, "values-ja/strings.xml"));
        String chinese = read(new File(res, "values-zh-rCN/strings.xml"));
        assertTrue(japanese.contains("ガイド付き"));
        assertTrue(chinese.contains("引导式"));
        for (String directory : DIRECTORIES) {
            String xml = read(new File(res, directory + "/strings.xml"));
            assertFalse(xml.contains("visual_scene_geometry/v1"));
            assertFalse(xml.contains("driver_sha256"));
            assertFalse(xml.contains("VK_ERROR_DEVICE_LOST"));
        }
    }

    private static Set<String> phase13Keys(String xml) {
        Matcher matcher = Pattern.compile(
                "<string name=\\\"((?:phase13_|help_|action_back|action_close|action_continue|action_finish)[^\\\"]*)\\\"")
                .matcher(xml);
        Set<String> keys = new HashSet<>();
        while (matcher.find()) keys.add(matcher.group(1));
        return keys;
    }

    private static File resourceDirectory() {
        File direct = new File("src/main/res");
        return direct.isDirectory() ? direct : new File("app/src/main/res");
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
