package com.runisys.orbit.spacevisibility.domain.downlink;

import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationCoordinate;
import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownlinkPlannerTest {

    /**
     * 带载荷重载的几何语义：标准对地载荷的距离范围会过滤站点可见窗口。
     * 该重载仅供领域工具与回归使用，下传生产主链路已不再读取载荷（实施方案 §3）。
     * 该几何下站点正处于卫星天底方向、站点仰角有效，但站星距离小于 1,000 km，
     * 因而被该重载的距离下限拒绝。
     */
    @Test
    void rejectsStationPassWhenStandardGroundPayloadRangeIsNotSatisfied() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        OrbitStateProvider provider = nadirPointingOrbit();
        GroundStationCoordinate station = stationBelow(provider.positionAt(start), start);
        PayloadConstraint groundPayload = PayloadConstraint.ground(
                1_000_000.0, 2_000_000.0, null, null, -91.0, 0.0, 10.0, 0.0, null);

        List<TimeInterval> windows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(5),
                1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, IlluminationRequirement.ANY, groundPayload);

        assertThat(windows).isEmpty();
    }

    /**
     * 与距离下限拒绝用例成对验证：同一站星几何满足 600 km 至 2,000 km 时必须保留窗口。
     * 这样测试不会因姿态窗口、太阳规避或仰角不通过而产生“空窗口”的假阳性。
     */
    @Test
    void retainsStationPassWhenStandardGroundPayloadRangeIsSatisfied() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        OrbitStateProvider provider = nadirPointingOrbit();
        GroundStationCoordinate station = stationBelow(provider.positionAt(start), start);
        PayloadConstraint groundPayload = PayloadConstraint.ground(
                600_000.0, 2_000_000.0, null, null, -91.0, 0.0, 10.0, 0.0, null);

        List<TimeInterval> windows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(5),
                1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, IlluminationRequirement.ANY, groundPayload);

        assertThat(windows).containsExactly(TimeInterval.of(0.0, 5.0));
    }

    /**
     * Word 文档已确认 WinTool 的启动角速度仅能保存、不参与下传计算。
     * 因而即使配置 0.5°/s，线性掠过站点的下传窗口也不能被延迟；持续跟踪仍只受 angleRate 约束。
     */
    @Test
    void ignoresGroundStartupAngleRateToMatchWinTool() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        OrbitStateProvider provider = linearGroundPass(start);
        GroundStationCoordinate station = stationBelow(provider.positionAt(start), start);
        PayloadConstraint groundPayload = PayloadConstraint.ground(
                0.0, 2_000_000.0, null, null, -91.0, 0.0, 10.0, 0.0, 0.5);

        List<TimeInterval> windows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(100),
                1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, IlluminationRequirement.ANY, groundPayload);

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).getStartSec()).isEqualTo(0.0);
    }

    /**
     * WinTool 不展示小于 30 秒的有效下传弧段。该阈值解释了文档中 A→牡丹江首个
     * “本影稳定 300 秒后仅余约 23 秒”的候选弧段没有出现在结果表中的现象。
     */
    @Test
    void dropsEffectiveDownlinkWindowShorterThanThirtySeconds() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Vector3 sunlitPosition = SolarGeometry.sunDirectionEci(start).scale(7_000_000.0);
        Vector3 shadowPosition = sunlitPosition.scale(-1.0);
        OrbitStateProvider provider = instant -> instant.isBefore(start) ? shadowPosition : sunlitPosition;
        GroundStationCoordinate station = stationBelow(sunlitPosition, start);

        List<TimeInterval> windows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(30),
                1.0, 1.0, 0.0, 0.0,
                1.0, 0.0, 0.0, IlluminationRequirement.SUNLIT);

        assertThat(windows).isEmpty();
    }

    @Test
    void appliesDefaultStyleGuardsToVisibleStationPass() {
        Instant start = Instant.parse("2000-01-01T12:00:00Z");
        OrbitStateProvider provider = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
        GroundStationCoordinate station = new GroundStationCoordinate(79.53938163, 0.0, 0.0);

        List<TimeInterval> windows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(5),
                1.0, 1.0, 10.0, 0.0,
                1.0, 1.0, 0.0);

        assertThat(windows).containsExactly(TimeInterval.of(1.0, 4.0));
    }

    @Test
    void filtersPassBySatelliteIlluminationRequirement() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Vector3 sunlitPosition = SolarGeometry.sunDirectionEci(start).scale(7_000_000.0);
        OrbitStateProvider provider = instant -> sunlitPosition;
        GroundStationCoordinate station = stationBelow(sunlitPosition, start);

        List<TimeInterval> sunlitWindows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(3),
                1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, IlluminationRequirement.SUNLIT);
        List<TimeInterval> shadowWindows = DownlinkPlanner.planEffectiveWindows(
                provider, station, start, start.plusSeconds(3),
                1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, IlluminationRequirement.SHADOW);

        assertThat(sunlitWindows).containsExactly(TimeInterval.of(0.0, 3.0));
        assertThat(shadowWindows).isEmpty();
    }

    private GroundStationCoordinate stationBelow(Vector3 position, Instant instant) {
        Vector3 ecef = GroundStationGeometry.eciToEcef(position, instant);
        double longitudeDeg = Math.toDegrees(Math.atan2(ecef.getY(), ecef.getX()));
        double latitudeDeg = Math.toDegrees(Math.atan2(ecef.getZ(),
                Math.sqrt(ecef.getX() * ecef.getX() + ecef.getY() * ecef.getY())));
        return new GroundStationCoordinate(longitudeDeg, latitudeDeg, 0.0);
    }

    /**
     * 提供一个固定在赤道上空、速度沿飞行方向的简化轨道，避免零速度使 LVLH 姿态轴退化。
     * 它只用于隔离验证对地载荷的距离边界，不模拟真实轨道传播。
     */
    private OrbitStateProvider nadirPointingOrbit() {
        return new OrbitStateProvider() {
            @Override
            public Vector3 positionAt(Instant instant) {
                return new Vector3(7_000_000.0, 0.0, 0.0);
            }

            @Override
            public Vector3 velocityAt(Instant instant) {
                return new Vector3(0.0, 7_500.0, 0.0);
            }
        };
    }

    /**
     * 简化的对地掠过轨道：位置沿 +Y 方向匀速移动，速度与位置保持一致，
     * 使测试能够稳定覆盖“初始角速度过高，随后下降”的启动捕获情形。
     */
    private OrbitStateProvider linearGroundPass(final Instant start) {
        return new OrbitStateProvider() {
            @Override
            public Vector3 positionAt(Instant instant) {
                double elapsedSec = instant.getEpochSecond() - start.getEpochSecond()
                        + (instant.getNano() - start.getNano()) / 1_000_000_000.0;
                return new Vector3(7_000_000.0, 7_500.0 * elapsedSec, 0.0);
            }

            @Override
            public Vector3 velocityAt(Instant instant) {
                return new Vector3(0.0, 7_500.0, 0.0);
            }
        };
    }
}
