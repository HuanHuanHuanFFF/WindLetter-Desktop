package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultKdfCalibratorTest {

    @Test
    void shouldChooseLargestMemoryWithinTargetThenScaleIterations() {
        List<VaultKdfParameters> measured = new ArrayList<>();
        Map<Integer, Long> perIterationMillis = Map.of(
            65_536, 60L,
            131_072, 130L,
            262_144, 300L
        );
        VaultKdfCalibrator calibrator = new VaultKdfCalibrator(
            500,
            262_144,
            parameters -> {
                measured.add(parameters);
                return perIterationMillis.get(parameters.memoryKiB()) * parameters.iterations();
            }
        );

        VaultKdfCalibration result = calibrator.calibrate();

        assertEquals(new VaultKdfParameters(131_072, 3, 1), result.parameters());
        assertEquals(390, result.measuredMillis());
        assertEquals(
            List.of(
                new VaultKdfParameters(65_536, 2, 1),
                new VaultKdfParameters(131_072, 2, 1),
                new VaultKdfParameters(262_144, 2, 1),
                new VaultKdfParameters(131_072, 3, 1)
            ),
            measured
        );
    }

    @Test
    void shouldKeepSecurityFloorWhenMinimumCostAlreadyExceedsTarget() {
        VaultKdfCalibrator calibrator = new VaultKdfCalibrator(
            500,
            262_144,
            parameters -> (long) parameters.iterations() * 450
        );

        VaultKdfCalibration result = calibrator.calibrate();

        assertEquals(VaultKdfParameters.minimumSupported(), result.parameters());
        assertEquals(900, result.measuredMillis());
    }

    @Test
    void shouldRespectMemoryCapAndIterationMaximum() {
        VaultKdfCalibrator calibrator = new VaultKdfCalibrator(
            5_000,
            131_072,
            parameters -> (long) parameters.iterations() * 20
        );

        VaultKdfCalibration result = calibrator.calibrate();

        assertEquals(new VaultKdfParameters(131_072, 10, 1), result.parameters());
        assertEquals(200, result.measuredMillis());
    }
}
