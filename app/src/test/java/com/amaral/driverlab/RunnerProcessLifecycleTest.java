package com.amaral.driverlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;

import org.junit.Test;

public final class RunnerProcessLifecycleTest {
    @Test
    public void destructionMarkerMustBelongToTheExactCompletedPid() throws Exception {
        JSONObject state = new JSONObject()
                .put("pid", 1234)
                .put("activity_destroyed_at_ms", 5678L);

        assertTrue(RunnerProcessLifecycle.activityDestroyed(state, 1234));
        assertFalse(RunnerProcessLifecycle.activityDestroyed(state, 1235));
        assertFalse(RunnerProcessLifecycle.activityDestroyed(new JSONObject()
                .put("pid", 1234), 1234));
        assertFalse(RunnerProcessLifecycle.activityDestroyed(null, 1234));
    }

    @Test
    public void coordinatorUsesBoundedStatePolling() {
        assertTrue(RunnerProcessLifecycle.ACTIVITY_DESTROY_TIMEOUT_MS > 0L);
        assertTrue(RunnerProcessLifecycle.PROCESS_EXIT_TIMEOUT_MS > 0L);
        assertTrue(RunnerProcessLifecycle.PROCESS_STATE_POLL_MS > 0L);
        assertTrue(RunnerProcessLifecycle.PROCESS_STATE_POLL_MS
                < RunnerProcessLifecycle.PROCESS_EXIT_TIMEOUT_MS);
    }
}
