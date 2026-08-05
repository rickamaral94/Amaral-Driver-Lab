package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class VisualSceneContractTest {
    @Test
    public void allVisibleScenesAreIndependentVersionOneSeries() throws Exception {
        assertEquals(3, VisualSceneContract.IDS.size());
        Set<String> hashes = new HashSet<>();
        for (String workloadId : VisualSceneContract.IDS) {
            JSONObject definition = VisualSceneContract.definition(workloadId);
            assertEquals(1, definition.getInt("scene_version"));
            assertEquals(960, definition.getInt("internal_width"));
            assertEquals(540, definition.getInt("internal_height"));
            assertEquals("p99_gpu_frame_ms", definition.getString("primary_metric"));
            assertEquals(3, definition.getJSONArray("checkpoint_frames").length());
            assertTrue(hashes.add(definition.getString("definition_sha256")));
            assertTrue(WorkloadContract.isSupported(workloadId));
            assertTrue(WorkloadContract.isPerformance(workloadId));
            assertTrue(WorkloadContract.lowerIsBetter(workloadId));
        }
    }

    @Test
    public void changingSceneDefinitionChangesItsCanonicalHash() throws Exception {
        JSONObject definition = VisualSceneContract.definition(VisualSceneContract.GEOMETRY_ID);
        String original = definition.getString("definition_sha256");
        definition.put("instance_count", 145);
        assertNotEquals(original,
                JsonCanonicalizer.sha256WithoutKey(definition, "definition_sha256"));
    }
}
