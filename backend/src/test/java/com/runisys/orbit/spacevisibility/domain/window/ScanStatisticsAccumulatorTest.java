package com.runisys.orbit.spacevisibility.domain.window;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScanStatisticsAccumulatorTest {

    /** 并行规划服务应能把多条链路的扫描量安全汇总为一条请求级日志。 */
    @Test
    void aggregatesStatisticsFromMultipleScannerInvocations() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ScanStatisticsAccumulator accumulator = new ScanStatisticsAccumulator();

        TimeWindowScanner.scanStates(start, start.plusSeconds(60), 60.0, 1.0, 10.0,
                instant -> "A", accumulator::record);
        TimeWindowScanner.scanStates(start, start.plusSeconds(60), 60.0, 1.0, 10.0,
                instant -> instant.isBefore(start.plusSeconds(30)) ? "A" : "B", accumulator::record);

        assertThat(accumulator.getScanCount()).isEqualTo(2L);
        assertThat(accumulator.getStateEvaluations()).isGreaterThan(4L);
        assertThat(accumulator.getAdaptiveProbeEvaluations()).isGreaterThan(0L);
        assertThat(accumulator.getRefinedRangeCount()).isEqualTo(1L);
    }
}
