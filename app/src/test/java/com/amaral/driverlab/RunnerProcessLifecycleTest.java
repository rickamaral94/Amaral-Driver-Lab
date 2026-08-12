package com.amaral.driverlab;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RunnerProcessLifecycleTest {
    @Test
    public void relaunchWaitsPastOldRunnerSelfTermination() {
        assertTrue(RunnerProcessLifecycle.hasSafeRelaunchGap());
        assertTrue(RunnerProcessLifecycle.RELAUNCH_DELAY_MS
                >= RunnerProcessLifecycle.VISUAL_COMPLETION_DELAY_MS
                + RunnerProcessLifecycle.SELF_TERMINATION_DELAY_MS
                + RunnerProcessLifecycle.RELAUNCH_SAFETY_MARGIN_MS);
    }
}
