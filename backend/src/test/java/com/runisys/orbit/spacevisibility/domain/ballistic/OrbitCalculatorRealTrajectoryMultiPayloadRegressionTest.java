package com.runisys.orbit.spacevisibility.domain.ballistic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class OrbitCalculatorRealTrajectoryMultiPayloadRegressionTest {
    // 数据文件位置自适应：交付目录为 source/../data/，上游仓库根为仓库根目录，取第一个存在的
    private static final Path REFERENCE_TRAJECTORY = resolveReferenceTrajectory();

    private static Path resolveReferenceTrajectory() {
        List<Path> candidates = Arrays.asList(
                Paths.get("..", "test-data", "reference", "ballistic", "trajectory-0.1s-standard.csv"),
                Paths.get("trajectory-0.1s-standard.csv"));
        for (Path candidate : candidates) {
            if (java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
    private static final long START_OFFSET_SEC = 1200L;
    private static final long OFFSET_STEP_SEC = 10L;
    private static final long NUM_CALCULATIONS = 11L;
    private static final double SENSOR_FOV_DEG = 140.0;

    @Test
    void embeddedPayloadsMatchLegacySinglePayloadForReferenceTrajectory() throws Exception {
        OrbitCalculator.ParsedBallisticCsv parsed =
                OrbitCalculator.parseBallisticCsvWithReference(REFERENCE_TRAJECTORY);

        OrbitCalculator.LaunchSearchResultV2 legacy = calculate(parsed,
                defaultSatellites());
        OrbitCalculator.LaunchSearchResultV2 onePayload = calculate(parsed,
                satellitesWithPayloads(1));
        OrbitCalculator.LaunchSearchResultV2 twoPayloads = calculate(parsed,
                satellitesWithPayloads(2));

        assertNotNull(legacy.best);
        assertFalse(legacy.best.visibleSatellites.isEmpty());
        assertEquivalent(legacy, onePayload, false);
        assertEquivalent(legacy, twoPayloads, true);
    }

    private static OrbitCalculator.LaunchSearchResultV2 calculate(
            OrbitCalculator.ParsedBallisticCsv parsed,
            List<OrbitCalculator.OrbitElements> satellites) {
        return OrbitCalculator.searchLaunchOffsetsV2(
                parsed.referenceEpochSec,
                START_OFFSET_SEC,
                OFFSET_STEP_SEC,
                NUM_CALCULATIONS,
                satellites,
                parsed.points,
                SENSOR_FOV_DEG,
                OrbitCalculator.OrbitModel.TWO_BODY,
                1.0,
                parsed.referenceEpochSec,
                0,
                OrbitCalculator.SensorVisibilityConfig.legacySimpleCone(SENSOR_FOV_DEG),
                null);
    }

    private static void assertEquivalent(OrbitCalculator.LaunchSearchResultV2 expected,
                                         OrbitCalculator.LaunchSearchResultV2 actual,
                                         boolean duplicatePayloads) {
        assertEquals(expected.allResults.size(), actual.allResults.size());
        assertEquals(expected.best.offsetSecond, actual.best.offsetSecond);
        assertEquals(expected.best.totalObservablePointCount()
                        * (duplicatePayloads ? 2L : 1L),
                actual.best.totalObservablePointCount());
        assertEquals(expected.best.totalObservableDurationSec()
                        * (duplicatePayloads ? 2.0 : 1.0),
                actual.best.totalObservableDurationSec(), 1e-9);

        for (int index = 0; index < expected.allResults.size(); index++) {
            OrbitCalculator.LaunchOffsetCalcResult expectedOffset = expected.allResults.get(index);
            OrbitCalculator.LaunchOffsetCalcResult actualOffset = actual.allResults.get(index);
            assertEquals(expectedOffset.offsetSecond, actualOffset.offsetSecond);
            assertEquals(expectedOffset.totalObservablePointCount()
                            * (duplicatePayloads ? 2L : 1L),
                    actualOffset.totalObservablePointCount());
            assertEquals(expectedOffset.totalObservableDurationSec()
                            * (duplicatePayloads ? 2.0 : 1.0),
                    actualOffset.totalObservableDurationSec(), 1e-9);

            int multiplier = duplicatePayloads ? 2 : 1;
            assertEquals(expectedOffset.visibleSatellites.size() * multiplier,
                    actualOffset.visibleSatellites.size());
            for (int arcIndex = 0; arcIndex < expectedOffset.visibleSatellites.size(); arcIndex++) {
                OrbitCalculator.SatelliteVisibilityResult expectedArc =
                        expectedOffset.visibleSatellites.get(arcIndex);
                for (int payloadIndex = 0; payloadIndex < multiplier; payloadIndex++) {
                    OrbitCalculator.SatelliteVisibilityResult actualArc =
                            actualOffset.visibleSatellites.get(arcIndex * multiplier + payloadIndex);
                    assertEquals(expectedArc.satelliteId, actualArc.satelliteId);
                    assertEquals(expectedArc.satelliteName, actualArc.satelliteName);
                    assertEquals(expectedArc.arcIndex, actualArc.arcIndex);
                    assertEquals(expectedArc.startJ2000Sec, actualArc.startJ2000Sec, 1e-9);
                    assertEquals(expectedArc.endJ2000Sec, actualArc.endJ2000Sec, 1e-9);
                    assertEquals(expectedArc.startRelativeTimeSec, actualArc.startRelativeTimeSec, 1e-9);
                    assertEquals(expectedArc.endRelativeTimeSec, actualArc.endRelativeTimeSec, 1e-9);
                    assertEquals(expectedArc.visiblePointCount, actualArc.visiblePointCount);
                    assertEquals("PAYLOAD-" + (payloadIndex + 1), actualArc.payloadId);
                }
            }
        }
    }

    private static List<OrbitCalculator.OrbitElements> defaultSatellites() {
        return Arrays.asList(defaultSatellite("SAT-A", 30.0), defaultSatellite("SAT-B", 0.0));
    }

    private static List<OrbitCalculator.OrbitElements> satellitesWithPayloads(int payloadCount) {
        List<OrbitCalculator.OrbitElements> satellites = new ArrayList<OrbitCalculator.OrbitElements>();
        satellites.add(withPayloads(defaultSatellite("SAT-A", 30.0), payloadCount));
        satellites.add(withPayloads(defaultSatellite("SAT-B", 0.0), payloadCount));
        return satellites;
    }

    private static OrbitCalculator.OrbitElements defaultSatellite(String id, double meanAnomalyDeg) {
        OrbitCalculator.OrbitElements satellite = new OrbitCalculator.OrbitElements();
        satellite.satelliteId = id;
        satellite.name = id;
        satellite.semiMajorAxisM = 7530612.0;
        satellite.eccentricity = 0.00085;
        satellite.inclinationDeg = 64.8;
        satellite.raanDeg = 120.0;
        satellite.argumentOfPerigeeDeg = 270.0;
        satellite.meanAnomalyDeg = meanAnomalyDeg;
        satellite.epochSec = OrbitCalculator.getDefaultSatelliteEpochSec();
        return satellite;
    }

    private static OrbitCalculator.OrbitElements withPayloads(OrbitCalculator.OrbitElements satellite,
                                                               int payloadCount) {
        for (int index = 1; index <= payloadCount; index++) {
            OrbitCalculator.SatellitePayloadConfig payload = new OrbitCalculator.SatellitePayloadConfig();
            payload.payloadId = "PAYLOAD-" + index;
            payload.payloadName = "Reference payload " + index;
            payload.sensorConfig = OrbitCalculator.SensorVisibilityConfig.legacySimpleCone(SENSOR_FOV_DEG);
            satellite.payloads.add(payload);
        }
        return satellite;
    }
}
