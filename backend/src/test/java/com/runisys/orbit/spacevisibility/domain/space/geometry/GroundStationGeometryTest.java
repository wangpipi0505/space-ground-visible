package com.runisys.orbit.spacevisibility.domain.space.geometry;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GroundStationGeometryTest {

    /**
     * 下传载荷评估统一在 TEME 惯性系内执行，地面站坐标需要先由 ECEF 逆旋转回 TEME。
     * 正反旋转必须互逆，否则站星距离、姿态窗口和太阳规避会在下传计算中使用错误的几何关系。
     */
    @Test
    void convertsEcefBackToOriginalEciAtSameInstant() {
        Instant instant = Instant.parse("2026-01-18T15:24:29.062Z");
        Vector3 originalEci = new Vector3(6_900_000.0, -1_200_000.0, 2_000_000.0);

        Vector3 restoredEci = GroundStationGeometry.ecefToEci(
                GroundStationGeometry.eciToEcef(originalEci, instant), instant);

        assertThat(restoredEci.getX()).isCloseTo(originalEci.getX(), offset(0.001));
        assertThat(restoredEci.getY()).isCloseTo(originalEci.getY(), offset(0.001));
        assertThat(restoredEci.getZ()).isCloseTo(originalEci.getZ(), offset(0.001));
    }

    @Test
    void returnsNinetyDegreesForSatelliteDirectlyAboveStation() {
        GroundStationCoordinate station = new GroundStationCoordinate(0.0, 0.0, 0.0);

        double elevationDeg = GroundStationGeometry.elevationDegFromEcef(
                station, new Vector3(6_878_137.0, 0.0, 0.0));

        assertThat(elevationDeg).isEqualTo(90.0);
    }

    /**
     * WGS84 在极区的短半轴小于赤道半径。两颗卫星之间的连线位于 6360 km 极轴高度时，
     * 已高于 WGS84 椭球而低于赤道半径球体：椭球口径应允许视通，不能误用赤道球体遮挡。
     */
    @Test
    void keepsLineOfSightForAPolarTangentAboveTheWgs84Ellipsoid() {
        Vector3 first = new Vector3(-7_000_000.0, 0.0, 6_360_000.0);
        Vector3 second = new Vector3(7_000_000.0, 0.0, 6_360_000.0);

        assertThat(GroundStationGeometry.hasLineOfSight(first, second)).isTrue();
    }

    /** 地面站恰在椭球表面是可见链路端点，浮点误差不能把它当作“地球内部”。 */
    @Test
    void keepsLineOfSightWhenTheGroundEndpointIsOnTheWgs84Surface() {
        Vector3 satellite = new Vector3(7_000_000.0, 0.0, 0.0);
        Vector3 groundEndpoint = new Vector3(6_378_137.0, 0.0, 0.0);

        assertThat(GroundStationGeometry.hasLineOfSight(satellite, groundEndpoint)).isTrue();
    }

    /** 地面站经过 ECI/ECEF 往返转换后仍是视通端点，不能因舍入落入椭球内部。 */
    @Test
    void keepsLineOfSightForAConvertedGroundStationSurfaceEndpoint() {
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        Vector3 satellite = new Vector3(7_000_000.0, 0.0, 0.0);
        Vector3 stationEcef = GroundStationGeometry.eciToEcef(satellite, instant)
                .scale(6_378_137.0 / 7_000_000.0);
        double longitudeDeg = Math.toDegrees(Math.atan2(stationEcef.getY(), stationEcef.getX()));
        GroundStationCoordinate station = new GroundStationCoordinate(longitudeDeg, 0.0, 0.0);
        Vector3 stationEci = GroundStationGeometry.ecefToEci(GroundStationGeometry.toEcef(station), instant);

        assertThat(GroundStationGeometry.hasLineOfSight(satellite, stationEci)).isTrue();
    }
}
