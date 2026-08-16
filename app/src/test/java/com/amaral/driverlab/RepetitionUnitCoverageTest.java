package com.amaral.driverlab;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Every workload the profile can schedule needs a repetition unit.
 *
 * <p>emulator_frame_pattern shipped in alpha28 registered everywhere except
 * here, so the step carrying 70% of the performance weight aborted at
 * consolidation with "Workload sem unidade de repetição" — after the suite had
 * already spent nine minutes on the gates. Enumerating the profile instead of
 * listing ids by hand is what keeps the next workload from repeating it.
 */
public final class RepetitionUnitCoverageTest {

    @Test
    public void everyWorkloadInEveryProfileHasARepetitionUnit() {
        for (int version : new int[] {1, 2, 3, 4, 5,
                Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION,
                Phase15DynamicRangeContract.PROFILE_VERSION}) {
            for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(version)) {
                if (!BenchmarkCalibrationContract.requiresCalibration(
                        step.workloadId, step.workloadVersion)) continue;
                assertNotNull("perfil v" + version + ", etapa " + step.stepId,
                        BenchmarkCalibrationContract.repetitionUnit(step.workloadId));
            }
        }
    }
}
