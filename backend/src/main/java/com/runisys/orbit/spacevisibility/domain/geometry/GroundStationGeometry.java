package com.runisys.orbit.spacevisibility.domain.geometry;

import java.time.Instant;

/**
 * WGS84站点几何及空间视线遮挡入口，位置单位米，仰角单位度。
 * 共用几何主体保留空间椭球及其相切/端点政策，不改用弹道历史球体口径。
 */
public final class GroundStationGeometry {

    private static final double EARTH_EQUATORIAL_RADIUS_M = 6_378_137.0;
    private static final double EARTH_FLATTENING = 1.0 / 298.257223563;
    private static final double EARTH_E2 = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING);

    private GroundStationGeometry() {
    }

    /**
     * WGS84站点到卫星的ECEF差向量投影到当地东/北/天顶轴，atan2(up,水平模)返回仰角度。
     * 输入位置米且必须同一时刻；这里不施加站点仰角范围或光照约束。
     */
    public static double elevationDegFromEcef(GroundStationCoordinate station, Vector3 satelliteEcef) {
        Vector3 stationEcef = toEcef(station);
        Vector3 delta = satelliteEcef.subtract(stationEcef);
        double latitude = Math.toRadians(station.getLatitudeDeg());
        double longitude = Math.toRadians(station.getLongitudeDeg());
        double east = -Math.sin(longitude) * delta.getX() + Math.cos(longitude) * delta.getY();
        double north = -Math.sin(latitude) * Math.cos(longitude) * delta.getX()
                - Math.sin(latitude) * Math.sin(longitude) * delta.getY()
                + Math.cos(latitude) * delta.getZ();
        double up = Math.cos(latitude) * Math.cos(longitude) * delta.getX()
                + Math.cos(latitude) * Math.sin(longitude) * delta.getY()
                + Math.sin(latitude) * delta.getZ();
        return Math.toDegrees(Math.atan2(up, Math.sqrt(east * east + north * north)));
    }

    /** 经纬度先由度转弧度，按WGS84卯酉圈曲率半径与站点椭球高（米）生成ECEF位置米。 */
    public static Vector3 toEcef(GroundStationCoordinate station) {
        double latitude = Math.toRadians(station.getLatitudeDeg());
        double longitude = Math.toRadians(station.getLongitudeDeg());
        double sinLatitude = Math.sin(latitude);
        double radius = EARTH_EQUATORIAL_RADIUS_M / Math.sqrt(1.0 - EARTH_E2 * sinLatitude * sinLatitude);
        return new Vector3(
                (radius + station.getHeightM()) * Math.cos(latitude) * Math.cos(longitude),
                (radius + station.getHeightM()) * Math.cos(latitude) * Math.sin(longitude),
                (radius * (1.0 - EARTH_E2) + station.getHeightM()) * sinLatitude);
    }

    /** 兼容旧ECI命名；输入实际为同一UTC时刻的TEME位置（米），委托原GMST主体。 */
    public static Vector3 eciToEcef(Vector3 eci, Instant instant) {
        return TemeEarthFixedFrames.temeToEcef(eci, instant);
    }

    /**
     * 将地固坐标旋转回同一 UTC 时刻的 TEME 惯性系。
     *
     * <p>下传的地面站仰角在 ECEF 系中计算，而对地载荷的距离、LVLH 姿态窗口、太阳规避和跟踪速率
     * 都在 {@link com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider} 返回的 TEME 系中计算。
     * 因此必须使用与 {@link #eciToEcef(Vector3, Instant)} 完全相反的 GMST 旋转；不能把 ECEF 站点
     * 位置直接与 TEME 卫星位置相减，否则会在地球自转角处引入系统性几何误差。</p>
     *
     * @param ecef 地面站的 WGS-84 ECEF 坐标，单位米
     * @param instant 坐标所属 UTC 时刻
     * @return 同一物理位置在 TEME 惯性系中的坐标，单位米
     */
    public static Vector3 ecefToEci(Vector3 ecef, Instant instant) {
        return TemeEarthFixedFrames.ecefToTeme(ecef, instant);
    }

    /**
     * 原空间 WGS84 遮挡适配：直接对同一时刻 TEME/ECI 位置（米）使用椭球度量。
     * 委托共用求交数学，同时保留空间相切求根、求根后内部端点排除及 1e-10 根端点容差。
     * 不追加坐标旋转；与弹道先旋转 ECEF 且相切可见的政策不同，不能互换。
     */
    public static boolean hasLineOfSight(Vector3 firstEci, Vector3 secondEci) {
        return GeometryMath.spaceLineOfSight(firstEci.getX(), firstEci.getY(), firstEci.getZ(),
                secondEci.getX(), secondEci.getY(), secondEci.getZ());
    }
}
