package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityArc;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityObserver;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairResult;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityTarget;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationConstraint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityPlanningServiceTest {

    /**
     * WinTool 的启动角速度允许保存但不参与目标观测。若把它当作每个光照状态子弧的
     * 建立门槛，文档弧段 6 中 A 星对 ONEWEB 的连续观测会被错误截断。
     */
    @Test
    void ignoresStartupAngleRateForTargetVisibilityToMatchWinTool() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            OrbitStateProvider observerOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            OrbitStateProvider targetOrbit = instant -> new Vector3(7_000_000.0,
                    1_000_000.0 + secondsSince(start, instant) * 1_000.0, 0.0);
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 10);

            List<VisibilityPairResult> results = service.plan(
                    Arrays.asList(new VisibilityObserver("A", observerOrbit,
                            PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null,
                                    10.0, 0.0, 0.0001))),
                    Arrays.asList(new VisibilityTarget("T", targetOrbit)), start, start.plusSeconds(5), 1.0, 1.0,
                    IlluminationConstraint.from("ANY", java.util.Collections.<String>emptyList(),
                            java.util.Collections.<String>emptyList()));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getArcs()).hasSize(1);
            assertThat(results.get(0).getArcs().get(0).getStartTime()).isEqualTo(start);
            assertThat(results.get(0).getArcs().get(0).getEndTime()).isEqualTo(start.plusSeconds(5));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 验证并行单星扫描将每段可见窗口的双方精确光照状态一并保留下来。 */
    @Test
    void recordsIlluminationDetailsForEveryVisiblePairArc() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OrbitStateProvider observerOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            OrbitStateProvider targetOrbit = instant -> new Vector3(7_000_000.0, 1_000_000.0, 0.0);
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 10);

            List<VisibilityPairResult> results = service.plan(
                    Arrays.asList(new VisibilityObserver("A", observerOrbit,
                            PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null))),
                    Arrays.asList(new VisibilityTarget("T", targetOrbit)),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:02Z"), 1.0, 0.001,
                    IlluminationConstraint.from("ANY", java.util.Collections.<String>emptyList(),
                            java.util.Collections.<String>emptyList()));

            VisibilityArc arc = results.get(0).getArcs().get(0);
            assertThat(arc.getObserverIlluminations()).hasSize(1);
            assertThat(arc.getObserverIlluminations().get(0).getSatelliteId()).isEqualTo("A");
            assertThat(arc.getTargetIllumination().getTargetId()).isEqualTo("T");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void calculatesPairWindowsInParallelAndSortsResults() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            OrbitStateProvider observerOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            OrbitStateProvider targetOrbit = instant -> new Vector3(7_000_000.0, 1_000_000.0, 0.0);
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 10);

            List<VisibilityPairResult> results = service.plan(
                    Arrays.asList(new VisibilityObserver("B", observerOrbit,
                                    PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null)),
                            new VisibilityObserver("A", observerOrbit,
                                    PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null))),
                    Arrays.asList(new VisibilityTarget("T-2", targetOrbit),
                            new VisibilityTarget("T-1", targetOrbit)),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:05Z"),
                    2.0,
                    1.0);

            assertThat(results).extracting(VisibilityPairResult::getObserverSatelliteId)
                    .containsExactly("A", "A", "B", "B");
            assertThat(results).allSatisfy(result -> assertThat(result.getArcs()).hasSize(1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void processesAllCatalogPairsInBatchesInsteadOfRejectingTheTotal() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            OrbitStateProvider observerOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            OrbitStateProvider targetOrbit = instant -> new Vector3(7_000_000.0, 1_000_000.0, 0.0);
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 1);

            List<VisibilityPairResult> results = service.plan(
                    Arrays.asList(new VisibilityObserver("A", observerOrbit,
                                    PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null)),
                            new VisibilityObserver("B", observerOrbit,
                                    PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null))),
                    Arrays.asList(new VisibilityTarget("T-1", targetOrbit),
                            new VisibilityTarget("T-2", targetOrbit)),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:05Z"),
                    2.0,
                    1.0);

            assertThat(results).hasSize(4);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 单个目标在扫描期的轨道传播异常不得终止同一批次中其他正常组合；正常目标的
     * 可见弧段仍应被保留，异常组合由上层结果模型另行记录为跳过项。
     */
    @Test
    void retainsHealthyPairWhenAnotherPairThrowsDuringScan() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            OrbitStateProvider observerOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            OrbitStateProvider healthyTargetOrbit = instant -> new Vector3(7_000_000.0, 1_000_000.0, 0.0);
            OrbitStateProvider failingTargetOrbit = instant -> {
                throw new IllegalStateException("synthetic propagation failure");
            };
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 2);

            List<VisibilityPairResult> results;
            try {
                results = service.plan(
                        Arrays.asList(new VisibilityObserver("A", observerOrbit,
                                PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null,
                                        0.0, 0.0, null))),
                        Arrays.asList(new VisibilityTarget("HEALTHY", healthyTargetOrbit),
                                new VisibilityTarget("FAILING", failingTargetOrbit)),
                        start, start.plusSeconds(2), 1.0, 1.0);
            } catch (RuntimeException exception) {
                // 当前实现会在 join() 处抛出；断言将该回归表现为测试失败而不是测试错误。
                results = java.util.Collections.<VisibilityPairResult>emptyList();
            }

            assertThat(results).extracting(VisibilityPairResult::getTargetId).containsExactly("HEALTHY");
        } finally {
            executor.shutdownNow();
        }
    }

    /** 验证同一次全量规划会复用观测星在相同时刻的外推结果，避免随目标数量重复外推。 */
    @Test
    void reusesObserverOrbitStatesAcrossDifferentTargetsInTheSamePlan() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger observerStateCalls = new AtomicInteger();
            OrbitStateProvider observerOrbit = instant -> {
                observerStateCalls.incrementAndGet();
                return new Vector3(7_000_000.0, 0.0, 0.0);
            };
            OrbitStateProvider targetOrbit = instant -> new Vector3(7_000_000.0, 1_000_000.0, 0.0);
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 1, 10);

            List<VisibilityPairResult> results = service.plan(
                    Arrays.asList(new VisibilityObserver("A", observerOrbit,
                            PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null))),
                    Arrays.asList(new VisibilityTarget("T-1", targetOrbit),
                            new VisibilityTarget("T-2", targetOrbit)),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:02Z"), 1.0, 1.0);

            assertThat(results).hasSize(2);
            assertThat(observerStateCalls).hasValue(5);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 将测试轨道相对规划起点的时间偏移转换为秒，保留亚秒精度。 */
    private static double secondsSince(Instant start, Instant instant) {
        return instant.getEpochSecond() - start.getEpochSecond()
                + (instant.getNano() - start.getNano()) / 1_000_000_000.0;
    }
}
