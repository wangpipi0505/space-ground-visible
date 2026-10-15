package com.runisys.orbit.spacevisibility.domain.space.visibility;

import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CombinationWindowCalculatorTest {

    @Test
    void returnsIntersectionOnlyWhenItMeetsCombinationDuration() {
        Map<String, List<TimeInterval>> windowsBySatellite = new HashMap<String, List<TimeInterval>>();
        windowsBySatellite.put("A", Collections.singletonList(TimeInterval.of(0.0, 500.0)));
        windowsBySatellite.put("B", Collections.singletonList(TimeInterval.of(100.0, 600.0)));

        CombinationConstraint constraint = new CombinationConstraint(Arrays.asList("A", "B"), 350.0);
        List<CombinationWindow> windows = CombinationWindowCalculator.calculate(windowsBySatellite, constraint);

        assertThat(windows).extracting(CombinationWindow::getInterval)
                .containsExactly(TimeInterval.of(100.0, 500.0));
    }

    /**
     * WinTool 以同一目标和观测组合的交集总时长判定门槛：两段 300s / 250s 虽各自不足
     * 400 秒，但累计 550 秒仍为合格结果，且原始中断边界必须保留给界面展示。
     */
    @Test
    void keepsSplitIntersectionsWhenTheirTotalDurationMeetsThreshold() {
        Map<String, List<TimeInterval>> windowsBySatellite = new HashMap<String, List<TimeInterval>>();
        windowsBySatellite.put("A", Arrays.asList(TimeInterval.of(0.0, 300.0), TimeInterval.of(400.0, 650.0)));
        windowsBySatellite.put("B", Arrays.asList(TimeInterval.of(0.0, 300.0), TimeInterval.of(400.0, 650.0)));
        windowsBySatellite.put("C", Arrays.asList(TimeInterval.of(0.0, 300.0), TimeInterval.of(400.0, 650.0)));

        List<CombinationWindow> windows = CombinationWindowCalculator.calculate(windowsBySatellite,
                new CombinationConstraint(Arrays.asList("A", "B", "C"), 400.0));

        assertThat(windows).extracting(CombinationWindow::getInterval)
                .containsExactly(TimeInterval.of(0.0, 300.0), TimeInterval.of(400.0, 650.0));
    }

    /** 累计达标后，单段较短的原始交集同样要保留，不能在输出层静默丢弃。 */
    @Test
    void keepsEveryOriginalFragmentAfterCumulativeDurationMeetsThreshold() {
        Map<String, List<TimeInterval>> windowsBySatellite = new HashMap<String, List<TimeInterval>>();
        windowsBySatellite.put("A", Arrays.asList(TimeInterval.of(0.0, 300.0), TimeInterval.of(500.0, 1000.0)));
        windowsBySatellite.put("B", Arrays.asList(TimeInterval.of(0.0, 300.0), TimeInterval.of(500.0, 1000.0)));

        List<CombinationWindow> windows = CombinationWindowCalculator.calculate(windowsBySatellite,
                new CombinationConstraint(Arrays.asList("A", "B"), 400.0));

        assertThat(windows).extracting(CombinationWindow::getInterval)
                .containsExactly(TimeInterval.of(0.0, 300.0), TimeInterval.of(500.0, 1000.0));
    }
}
