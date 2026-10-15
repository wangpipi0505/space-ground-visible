package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPair;
import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPairResult;

import com.runisys.orbit.spacevisibility.domain.space.downlink.IlluminationRequirement;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationCoordinate;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DownlinkPlanningServiceTest {

    @Test
    void plansStationPairsInParallelAndSortsResults() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DownlinkPlanningService service = new DownlinkPlanningService(executor, 2);
            OrbitStateProvider orbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            GroundStationCoordinate station = new GroundStationCoordinate(79.53938163, 0.0, 0.0);
            Instant start = Instant.parse("2000-01-01T12:00:00Z");

            List<DownlinkPairResult> results = service.plan(Arrays.asList(
                    pair(2L, "ST-B", orbit, station, start),
                    pair(1L, "ST-A", orbit, station, start)));

            assertThat(results).extracting(DownlinkPairResult::getSatelliteId).containsExactly(1L, 2L);
            assertThat(results).allSatisfy(result -> assertThat(result.getWindows()).hasSize(1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsPairCountOverConfiguredLimit() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            DownlinkPlanningService service = new DownlinkPlanningService(executor, 1);
            OrbitStateProvider orbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            GroundStationCoordinate station = new GroundStationCoordinate(79.53938163, 0.0, 0.0);
            Instant start = Instant.parse("2000-01-01T12:00:00Z");

            assertThatIllegalArgumentException().isThrownBy(() -> service.plan(Arrays.asList(
                    pair(1L, "ST-A", orbit, station, start),
                    pair(2L, "ST-B", orbit, station, start))));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 下传主链路只使用站点仰角和卫星光照约束；即使保存了会排除该站星距离的对地载荷，
     * 也不得影响下传窗口。对地载荷仍可由领域工具重载单独验证，但不应由本服务传入。
     */
    @Test
    void plansPairUsingOnlyStationAndIlluminationConstraints() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            DownlinkPlanningService service = new DownlinkPlanningService(executor, 1);
            Instant start = Instant.parse("2000-01-01T12:00:00Z");
            OrbitStateProvider orbit = new OrbitStateProvider() {
                @Override
                public Vector3 positionAt(Instant instant) {
                    return new Vector3(7_000_000.0, 0.0, 0.0);
                }

                @Override
                public Vector3 velocityAt(Instant instant) {
                    return new Vector3(0.0, 7_500.0, 0.0);
                }
            };
            GroundStationCoordinate station = new GroundStationCoordinate(79.53938163, 0.0, 0.0);
            DownlinkPair pair = new DownlinkPair(1L, "ST-A", orbit, station,
                    start, start.plusSeconds(5), 1.0, 1.0,
                    10.0, 0.0, 0.0, 0.0, 0.0, IlluminationRequirement.ANY);

            assertThat(service.plan(Arrays.asList(pair)).get(0).getWindows())
                    .containsExactly(TimeInterval.of(0.0, 5.0));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 单条站星链路计算异常不能使同一批次内的正常下传窗口丢失。 */
    @Test
    void retainsHealthyLinkWhenAnotherLinkThrowsDuringPlanning() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Instant start = Instant.parse("2000-01-01T12:00:00Z");
            GroundStationCoordinate station = new GroundStationCoordinate(79.53938163, 0.0, 0.0);
            OrbitStateProvider healthyOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
            OrbitStateProvider failingOrbit = instant -> {
                throw new IllegalStateException("synthetic propagation failure");
            };
            DownlinkPlanningService service = new DownlinkPlanningService(executor, 2);

            List<DownlinkPairResult> results;
            try {
                results = service.plan(Arrays.asList(
                        pair(1L, "HEALTHY", healthyOrbit, station, start),
                        pair(2L, "FAILING", failingOrbit, station, start)));
            } catch (RuntimeException exception) {
                // 当前实现会在 join() 处抛出；断言将该回归表现为测试失败而不是测试错误。
                results = java.util.Collections.<DownlinkPairResult>emptyList();
            }

            assertThat(results).extracting(DownlinkPairResult::getSatelliteId).containsExactly(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private DownlinkPair pair(Long satelliteId,
                              String stationId,
                              OrbitStateProvider orbit,
                              GroundStationCoordinate station,
                              Instant start) {
        return new DownlinkPair(satelliteId, stationId, orbit, station,
                start, start.plusSeconds(5), 1.0, 1.0,
                10.0, 0.0, 0.0, 0.0, 0.0, IlluminationRequirement.ANY);
    }
}
