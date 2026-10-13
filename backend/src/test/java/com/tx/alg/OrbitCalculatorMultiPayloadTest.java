package com.tx.alg;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class OrbitCalculatorMultiPayloadTest {

    @Test
    void 同一卫星的多个载荷应分别计算并按载荷分组输出() {
        double epoch = OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00.000");
        OrbitCalculator.OrbitElements satellite = defaultSatellite(epoch);
        satellite.payloads.add(payload("PAYLOAD-GS", "对地监视载荷", "对地监视"));
        satellite.payloads.add(payload("PAYLOAD-GT", "对地跟踪载荷", "对地跟踪"));

        List<OrbitCalculator.BallisticPoint> points = Arrays.asList(
                targetAtSatelliteNadir(satellite, epoch, 0.0),
                targetAtSatelliteNadir(satellite, epoch + 1.0, 1.0));

        OrbitCalculator.LaunchSearchResultV2 result = OrbitCalculator.searchLaunchOffsetsV2(
                epoch,
                0L,
                1L,
                1L,
                Arrays.asList(satellite),
                points,
                140.0,
                OrbitCalculator.OrbitModel.TWO_BODY,
                1.0,
                epoch,
                1,
                OrbitCalculator.SensorVisibilityConfig.forPayload("对地监视", 0.0, 0.0),
                null);

        assertEquals(2, result.best.visibleSatellites.size());
        assertEquals("PAYLOAD-GS", result.best.visibleSatellites.get(0).payloadId);
        assertEquals("PAYLOAD-GT", result.best.visibleSatellites.get(1).payloadId);

        String json = result.toJson();
        assertTrue(json.contains("\"payloadResults\""));
        assertTrue(json.contains("\"satelliteId\": \"SAT-A\""));
        assertTrue(json.contains("\"payloadId\": \"PAYLOAD-GS\""));
        assertTrue(json.contains("\"payloadId\": \"PAYLOAD-GT\""));
    }

    private static OrbitCalculator.OrbitElements defaultSatellite(double epoch) {
        OrbitCalculator.OrbitElements satellite = new OrbitCalculator.OrbitElements();
        satellite.satelliteId = "SAT-A";
        satellite.name = "SAT-A";
        satellite.semiMajorAxisM = 7_530_612.0;
        satellite.eccentricity = 0.00085;
        satellite.inclinationDeg = 64.8;
        satellite.raanDeg = 120.0;
        satellite.argumentOfPerigeeDeg = 270.0;
        satellite.meanAnomalyDeg = 30.0;
        satellite.epochSec = epoch;
        return satellite;
    }

    private static OrbitCalculator.SatellitePayloadConfig payload(String id, String name, String type) {
        OrbitCalculator.SatellitePayloadConfig payload = new OrbitCalculator.SatellitePayloadConfig();
        payload.payloadId = id;
        payload.payloadName = name;
        payload.sensorConfig = OrbitCalculator.SensorVisibilityConfig.forPayload(type, 0.0, 0.0);
        return payload;
    }

    private static OrbitCalculator.BallisticPoint targetAtSatelliteNadir(OrbitCalculator.OrbitElements satellite,
                                                                          double absoluteTime,
                                                                          double relativeTime) {
        OrbitCalculator.Vector3 satellitePosition = OrbitCalculator.propagateAtAbsolute(
                satellite, absoluteTime, OrbitCalculator.OrbitModel.TWO_BODY);
        OrbitCalculator.Vector3 targetPosition = satellitePosition.scale(6_800_000.0 / satellitePosition.norm());
        OrbitCalculator.BallisticPoint point = new OrbitCalculator.BallisticPoint();
        point.tSec = relativeTime;
        OrbitCalculator.Vector3 targetEcef = OrbitCalculator.eciToEcefPos(targetPosition, absoluteTime);
        point.ecefX = targetEcef.x;
        point.ecefY = targetEcef.y;
        point.ecefZ = targetEcef.z;
        return point;
    }
}
