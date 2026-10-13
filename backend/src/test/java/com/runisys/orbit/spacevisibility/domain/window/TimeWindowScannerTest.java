package com.runisys.orbit.spacevisibility.domain.window;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class TimeWindowScannerTest {

    /** 验证状态扫描器使用毫秒精选步长定位光照状态切换时刻。 */
    @Test
    void splitsStableStatesAtMillisecondFineStep() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        List<StateInterval<String>> intervals = TimeWindowScanner.scanStates(
                start, start.plusSeconds(2), 1.0, 0.001,
                instant -> instant.isBefore(start.plusMillis(1234)) ? "UMBRA" : "SUNLIT");

        assertThat(intervals).extracting(StateInterval::getState).containsExactly("UMBRA", "SUNLIT");
        assertThat(intervals.get(0).getInterval().getEndSec()).isCloseTo(1.234, offset(0.000000001));
    }

    @Test
    void refinesCoarseVisibilityTransitionsWithFineStep() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        List<TimeInterval> windows = TimeWindowScanner.scan(
                start,
                start.plusSeconds(30),
                10.0,
                1.0,
                instant -> {
                    long offset = instant.getEpochSecond() - start.getEpochSecond();
                    return offset >= 7 && offset <= 23;
                });

        assertThat(windows).containsExactly(TimeInterval.of(7.0, 23.0));
    }

    /**
     * 两个粗选端点都不可见时仍须自适应细分：大于和等于最小关注时长的窄可见弧应被检出，
     * 小于该时长、且完全落在采样间隙内的弧段按接口约定忽略。
     */
    @Test
    void findsOnlyVisibilityPulsesAtOrAboveConfiguredMinimumEventDuration() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        List<TimeInterval> longerPulse = TimeWindowScanner.scan(start, start.plusSeconds(60),
                60.0, 1.0, 10.0, instant -> offsetSeconds(start, instant) >= 20.0
                        && offsetSeconds(start, instant) <= 40.0);
        List<TimeInterval> exactPulse = TimeWindowScanner.scan(start, start.plusSeconds(60),
                60.0, 1.0, 10.0, instant -> offsetSeconds(start, instant) >= 20.0
                        && offsetSeconds(start, instant) <= 30.0);
        List<TimeInterval> shorterPulse = TimeWindowScanner.scan(start, start.plusSeconds(60),
                60.0, 1.0, 10.0, instant -> offsetSeconds(start, instant) >= 16.0
                        && offsetSeconds(start, instant) <= 21.0);

        assertThat(longerPulse).containsExactly(TimeInterval.of(20.0, 40.0));
        assertThat(exactPulse).containsExactly(TimeInterval.of(20.0, 30.0));
        assertThat(shorterPulse).isEmpty();
    }

    /** A→B→A 的两端都是 A 时，B 段达到最小时长仍须拆出，不能与前后 A 段粘合。 */
    @Test
    void splitsIntermediateStateWhenCoarseEndpointsAreEqual() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        List<StateInterval<String>> intervals = TimeWindowScanner.scanStates(start, start.plusSeconds(60),
                60.0, 1.0, 10.0, instant -> offsetSeconds(start, instant) >= 20.0
                        && offsetSeconds(start, instant) < 40.0 ? "B" : "A");
        List<StateInterval<String>> subMinimumIntervals = TimeWindowScanner.scanStates(start, start.plusSeconds(60),
                60.0, 1.0, 10.0, instant -> offsetSeconds(start, instant) >= 16.0
                        && offsetSeconds(start, instant) < 21.0 ? "B" : "A");

        assertThat(intervals).extracting(StateInterval::getState).containsExactly("A", "B", "A");
        assertThat(intervals).extracting(item -> item.getInterval()).containsExactly(
                TimeInterval.of(0.0, 20.0), TimeInterval.of(20.0, 40.0), TimeInterval.of(40.0, 60.0));
        assertThat(subMinimumIntervals).extracting(StateInterval::getState).containsExactly("A");
        assertThat(subMinimumIntervals.get(0).getInterval()).isEqualTo(TimeInterval.of(0.0, 60.0));
    }

    /**
     * 扫描统计必须由本次调用返回给上层汇总，而不是在每个组合内单独打印日志；否则全目标规划会产生日志洪泛。
     */
    @Test
    void reportsAdaptiveSamplingStatisticsToTheCaller() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<TimeWindowScanner.ScanStatistics> statistics = new java.util.ArrayList<TimeWindowScanner.ScanStatistics>();

        TimeWindowScanner.scanStates(start, start.plusSeconds(60), 60.0, 1.0, 10.0,
                instant -> offsetSeconds(start, instant) >= 20.0 && offsetSeconds(start, instant) < 40.0
                        ? "B" : "A",
                statistics::add);

        assertThat(statistics).hasSize(1);
        assertThat(statistics.get(0).getStateEvaluations()).isGreaterThan(2L);
        assertThat(statistics.get(0).getAdaptiveProbeEvaluations()).isGreaterThan(0L);
        assertThat(statistics.get(0).getRefinedRangeCount()).isEqualTo(1L);
    }

    /** 可见窗口重载也必须透传统计回调，供下传规划服务在请求结束时集中写日志。 */
    @Test
    void reportsSamplingStatisticsForVisibilityWindowScan() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<TimeWindowScanner.ScanStatistics> statistics = new java.util.ArrayList<TimeWindowScanner.ScanStatistics>();

        List<TimeInterval> windows = TimeWindowScanner.scan(start, start.plusSeconds(60), 60.0, 1.0, 10.0,
                instant -> offsetSeconds(start, instant) >= 20.0 && offsetSeconds(start, instant) <= 40.0,
                statistics::add);

        assertThat(windows).containsExactly(TimeInterval.of(20.0, 40.0));
        assertThat(statistics).hasSize(1);
        assertThat(statistics.get(0).getAdaptiveProbeEvaluations()).isGreaterThan(0L);
    }

    private double offsetSeconds(Instant start, Instant instant) {
        return instant.getEpochSecond() - start.getEpochSecond()
                + (instant.getNano() - start.getNano()) / 1_000_000_000.0;
    }
}
