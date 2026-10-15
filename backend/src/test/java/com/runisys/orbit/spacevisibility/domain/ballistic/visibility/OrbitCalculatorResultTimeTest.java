package com.runisys.orbit.spacevisibility.domain.ballistic.visibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class OrbitCalculatorResultTimeTest {

    @Test
    void visibleArc输出使用弧段自身的开始和结束时间() {
        OrbitCalculator.LaunchOffsetCalcResult result = new OrbitCalculator.LaunchOffsetCalcResult();
        result.offsetSecond = 120L;
        result.launchTimeUtc = "2026/07/01 10:00:00.000";
        result.launchTimeJ2000Sec = 836049600.0;

        OrbitCalculator.SatelliteVisibilityResult arc = new OrbitCalculator.SatelliteVisibilityResult();
        arc.satelliteName = "SAT-A";
        arc.satelliteIndex = 0;
        arc.arcIndex = 1;
        arc.targetId = "T-1";
        arc.targetName = "目标一";
        arc.launchUTC = "2026/07/01 10:00:00.000";
        arc.launchTimeJ2000Sec = 836049600.0;
        arc.startJ2000Sec = 836049700.0;
        arc.endJ2000Sec = 836049760.0;
        arc.visiblePointCount = 2;
        result.visibleSatellites.add(arc);

        String json = result.toJson();

        assertTrue(json.contains("\"startJ2000Sec\": 836049700"));
        assertTrue(json.contains("\"endJ2000Sec\": 836049760"));
        assertTrue(json.contains("\"startTime\""));
        assertTrue(json.contains("\"endTime\""));
    }

    @Test
    void 可见窗口结果输出载荷分类和可见性约束摘要() {
        OrbitCalculator.LaunchOffsetCalcResult result = new OrbitCalculator.LaunchOffsetCalcResult();
        result.offsetSecond = 0L;
        result.launchTimeUtc = "2026/07/01 10:00:00.000";
        result.launchTimeJ2000Sec = 836049600.0;
        result.payloadType = "对空跟踪";
        result.visibilityConstraint = OrbitCalculator.VisibilityConstraintSummary.fromConfig(
                OrbitCalculator.SensorVisibilityConfig.forPayload("对空跟踪", 1200.0, 45.0));

        OrbitCalculator.SatelliteVisibilityResult arc = new OrbitCalculator.SatelliteVisibilityResult();
        arc.satelliteName = "SAT-A";
        arc.satelliteIndex = 0;
        arc.arcIndex = 1;
        arc.targetId = "T-1";
        arc.targetName = "目标一";
        arc.payloadType = "对空跟踪";
        arc.startJ2000Sec = 836049700.0;
        arc.endJ2000Sec = 836049760.0;
        arc.visiblePointCount = 2;
        result.visibleSatellites.add(arc);

        OrbitCalculator.LaunchSearchResultV2 searchResult =
                new OrbitCalculator.LaunchSearchResultV2(
                        java.util.Collections.singletonList(result),
                        result,
                        "2026/07/01 10:00:00.000",
                        result.visibilityConstraint);

        String json = searchResult.toJson();

        assertTrue(json.contains("\"visibilityConstraint\""));
        assertTrue(json.contains("\"payloadType\": \"对空跟踪\""));
        assertTrue(json.contains("\"maxDetectRangeKm\": 1200"));
        assertTrue(json.contains("\"sunExclusionAngleDeg\": 45"));
        assertTrue(json.contains("\"innerConeAngleDeg\": 53"));
        assertTrue(json.contains("\"outerConeAngleDeg\": 150"));
    }
}
