package com.tx.alg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class OrbitCalculatorVisibilityStabilityTest {

    @Test
    void 单个可见采样点不形成稳定可见弧段() {
        OrbitCalculator.SatelliteVisibilityResult singlePointArc =
                new OrbitCalculator.SatelliteVisibilityResult();
        singlePointArc.satelliteName = "SAT-A";
        singlePointArc.arcIndex = 1;
        singlePointArc.visiblePointCount = 1;
        singlePointArc.startRelativeTimeSec = 10.0;
        singlePointArc.endRelativeTimeSec = 10.0;

        OrbitCalculator.SatelliteVisibilityResult stableArc =
                new OrbitCalculator.SatelliteVisibilityResult();
        stableArc.satelliteName = "SAT-A";
        stableArc.arcIndex = 2;
        stableArc.visiblePointCount = 3;
        stableArc.startRelativeTimeSec = 20.0;
        stableArc.endRelativeTimeSec = 22.0;

        List<OrbitCalculator.SatelliteVisibilityResult> arcs =
                new ArrayList<OrbitCalculator.SatelliteVisibilityResult>();
        arcs.add(singlePointArc);
        arcs.add(stableArc);

        List<OrbitCalculator.SatelliteVisibilityResult> filtered =
                OrbitCalculator.stableVisibleArcs(arcs, 2, 0.001);

        assertEquals(1, filtered.size());
        assertEquals(2, filtered.get(0).arcIndex);
    }

    @Test
    void 对地载荷使用默认半角并叠加探测距离() {
        OrbitCalculator.Vector3 satellite = new OrbitCalculator.Vector3(7_000_000.0, 0.0, 0.0);
        OrbitCalculator.SensorVisibilityConfig config =
                OrbitCalculator.SensorVisibilityConfig.forPayload(OrbitCalculator.PayloadType.GROUND_SURVEILLANCE);
        config.sunExclusionAngleDeg = 0.0;
        config.maxDetectRangeM = 1_200_000.0;

        assertTrue(OrbitCalculator.isTargetObservableFromSatellite(
                satellite,
                targetAtNadirAngle(satellite, 50.0, 1_000_000.0),
                config,
                0.0,
                new OrbitCalculator.Vector3(0.0, 1.0, 0.0)));
        assertFalse(OrbitCalculator.isTargetObservableFromSatellite(
                satellite,
                targetAtNadirAngle(satellite, 60.0, 1_000_000.0),
                config,
                0.0,
                new OrbitCalculator.Vector3(0.0, 1.0, 0.0)));
        assertFalse(OrbitCalculator.isTargetObservableFromSatellite(
                satellite,
                targetAtNadirAngle(satellite, 20.0, 1_500_000.0),
                config,
                0.0,
                new OrbitCalculator.Vector3(0.0, 1.0, 0.0)));
    }

    @Test
    void 对空监视载荷使用内外角环形视场() {
        OrbitCalculator.Vector3 satellite = new OrbitCalculator.Vector3(7_000_000.0, 0.0, 0.0);
        OrbitCalculator.SensorVisibilityConfig config =
                OrbitCalculator.SensorVisibilityConfig.forPayload(OrbitCalculator.PayloadType.AIR_SURVEILLANCE);
        config.sunExclusionAngleDeg = 0.0;

        assertFalse(OrbitCalculator.isTargetObservableFromSatellite(
                satellite,
                targetAtNadirAngle(satellite, 50.0, 1_000_000.0),
                config,
                0.0,
                new OrbitCalculator.Vector3(0.0, 1.0, 0.0)));
        assertTrue(OrbitCalculator.isTargetObservableFromSatellite(
                satellite,
                targetAtNadirAngle(satellite, 65.0, 1_000_000.0),
                config,
                0.0,
                new OrbitCalculator.Vector3(0.0, 1.0, 0.0)));
        assertFalse(OrbitCalculator.isTargetObservableFromSatellite(
                satellite,
                targetAtNadirAngle(satellite, 85.0, 1_000_000.0),
                config,
                0.0,
                new OrbitCalculator.Vector3(0.0, 1.0, 0.0)));
    }

    @Test
    void 视线方向与太阳方向夹角小于阈值时不可见() {
        OrbitCalculator.Vector3 satellite = new OrbitCalculator.Vector3(7_000_000.0, 0.0, 0.0);
        OrbitCalculator.Vector3 target = targetAtNadirAngle(satellite, 10.0, 500_000.0);
        OrbitCalculator.Vector3 lineOfSight = target.subtract(satellite);
        OrbitCalculator.SensorVisibilityConfig config =
                OrbitCalculator.SensorVisibilityConfig.forPayload(OrbitCalculator.PayloadType.GROUND_TRACKING);
        config.sunExclusionAngleDeg = 45.0;

        assertFalse(OrbitCalculator.isTargetObservableFromSatellite(
                satellite, target, config, 0.0, lineOfSight));
        assertTrue(OrbitCalculator.isTargetObservableFromSatellite(
                satellite, target, config, 0.0, new OrbitCalculator.Vector3(0.0, 0.0, 1.0)));
    }

    private static OrbitCalculator.Vector3 targetAtNadirAngle(OrbitCalculator.Vector3 satellite,
                                                              double angleDeg,
                                                              double rangeM) {
        double angle = Math.toRadians(angleDeg);
        OrbitCalculator.Vector3 direction = new OrbitCalculator.Vector3(
                -Math.cos(angle),
                Math.sin(angle),
                0.0);
        return satellite.add(direction.scale(rangeM));
    }
}
