package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.payload.IlluminationState;
import com.runisys.orbit.spacevisibility.domain.payload.VisibilitySample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WinTool 两段式报告格式化器回归：以对方 2026-09-01 反馈的 GLOBALSTAR M091 实测值
 * （A 星距离 2148.28~2597.37km、方位(相对-X) 72.8344~87.1653°、俯仰 93.942~98.7201°、
 * 最大相对角速度 0.0396068°/s）为输入基准，验证换算与输出格式。
 */
class WinToolReportFormatterTest {

    @Test
    void formatsTwoPartReportWithPerSatelliteStatistics() {
        Instant start = Instant.parse("2026-09-01T16:24:34Z");
        Instant end = Instant.parse("2026-09-01T16:38:12Z");

        // A 星统计：LVLH 方位 252.8344°（+180 归一后 72.8344°）、水平俯仰 3.942°（+90 后 93.942°）。
        VisibilityPairStatistics statsA = new VisibilityPairStatistics();
        statsA.accept(sample(2148280.0, 252.8344, 3.942, 0.0396068));
        statsA.accept(sample(2597370.0, 267.1653, 8.7201, 0.0200000));

        VisibilityArc.ObserverIllumination observerA =
                new VisibilityArc.ObserverIllumination("2068", "SHIYAN 20A (SY-20A)", IlluminationState.SUNLIT);
        VisibilityArc.TargetIllumination targetIllumination =
                new VisibilityArc.TargetIllumination("24739", "GLOBALSTAR M091", IlluminationState.UMBRA);
        VisibilityArc pairArc = new VisibilityArc(start, end,
                Collections.singletonList(observerA), targetIllumination);
        VisibilityPairResult pairA = new VisibilityPairResult("2068", "24739",
                Collections.singletonList(pairArc), statsA);

        VisibilityArc.SatelliteSegment segmentA = new VisibilityArc.SatelliteSegment("2068",
                "SHIYAN 20A (SY-20A)", "SUNLIT", "阳照", start, end, 818.0, statsA);
        VisibilityArc combinationArc = new VisibilityArc(start, end,
                Collections.singletonList(observerA), targetIllumination)
                .withSatelliteSegments(Collections.singletonList(segmentA));
        VisibilityObservationResult result = new VisibilityObservationResult("24739", "GLOBALSTAR M091",
                Collections.singletonList("2068"), Collections.singletonList("SHIYAN 20A (SY-20A)"),
                Collections.singletonList(combinationArc));

        Map<String, String> letters = new LinkedHashMap<String, String>();
        letters.put("2068", "A");
        Map<String, WinToolReportFormatter.TargetInfo> targetInfo = new LinkedHashMap<String, WinToolReportFormatter.TargetInfo>();
        targetInfo.put("24739", new WinToolReportFormatter.TargetInfo("GLOBALSTAR M091", 5842.26,
                "1 37741U 11033C 26222.92867949 -.00000096 00000-0 43561-4 0 9997",
                "2 37741 51.9996 75.2585 0000935 71.0130 35.8077 12.62264499696190"));

        String report = WinToolReportFormatter.format(
                Collections.singletonList(result),
                Collections.singletonList(pairA),
                letters, targetInfo);

        assertThat(report)
                .contains("一、目标+各星观测时段列表")
                .contains("二、摘要/详细信息")
                .contains("Mission 1 : 0 GLOBALSTAR M091(GLOBALSTAR M091,5842.26kg)")
                .contains("1 37741U 11033C 26222.92867949 -.00000096 00000-0 43561-4 0 9997")
                .contains("任务摘要:")
                // 单星时长为原始窗口累计：16:24:34~16:38:12 = 818 秒
                .contains("A(818.000)")
                .contains("A星情况:")
                .contains("1:2026-09-01T16:24:34~2026-09-01T16:38:12(818s)")
                .contains("A星距离范围: 2148.28km ~ 2597.37km")
                // LVLH 方位 252.8344° + 180° 归一到 [-180,180) = 72.8344°，与 WinTool 文档一致
                .contains("A星方位(相对-X): 72.8344度 ~ 87.1653度")
                // 水平俯仰 3.942° + 90° = 93.942°（视线与天底夹角）
                .contains("A星俯仰:93.9420度 ~ 98.7201度")
                .contains("A星最大相对角速度: 0.0396068度/秒");
    }

    /** 构造一个几何可见的采样点；角度/距离取值与对方 GLOBALSTAR M091 实测口径一致。 */
    private static VisibilitySample sample(double rangeM, double azimuthDeg, double elevationDeg,
                                           double rateDegPerSec) {
        return new VisibilitySample(true, IlluminationState.SUNLIT, IlluminationState.UMBRA,
                rateDegPerSec, rangeM, azimuthDeg, elevationDeg);
    }
}
