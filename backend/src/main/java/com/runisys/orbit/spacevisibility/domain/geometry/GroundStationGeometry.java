package com.runisys.orbit.spacevisibility.domain.geometry;

import java.time.Instant;

public final class GroundStationGeometry {

    private static final double EARTH_EQUATORIAL_RADIUS_M = 6_378_137.0;
    private static final double EARTH_FLATTENING = 1.0 / 298.257223563;
    private static final double EARTH_E2 = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING);
    /** 坐标旋转后地表端点的椭球归一化误差量级远小于该值，不能被误判为遮挡。 */
    private static final double ELLIPSOID_SURFACE_TOLERANCE = 1.0e-12;
    /** 线段参数的端点容差，避免求根舍入把地表端点写成线段内部交点。 */
    private static final double LINE_OF_SIGHT_ENDPOINT_TOLERANCE = 1.0e-10;

    private GroundStationGeometry() {
    }

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
     * 判断两点之间的线段是否不被 WGS-84 地球椭球遮挡。
     *
     * <p>把线段参数化为 P(t) = firstEci + t·segment（t∈[0,1]），代入旋转椭球方程
     * x²/a² + z²/c² = 1（a=赤道半径，c=极半径；旋转椭球绕 z 轴对称，y 项并入 x²/a²），
     * 得到关于 t 的一元二次方程 q·t² + l·t + c0 = 0，三个系数分别为下面代码中的
     * quadratic、linear、constant。判别式 < 0 时直线与椭球无交点，必然视通；
     * 否则求出两根，仅当某根落在开区间 (0,1) 内（遮挡点严格位于两端点之间）才判遮挡。
     * 另有两个前置排除：任一端点已进入椭球内部（如卫星轨道退化）直接不可见；
     * 端点恰在椭球面上（地面站）由端点容差保护，不因求根舍入误判为内部。</p>
     *
     * @param firstEci 第一端点（观测星或地面站）的 TEME/ECI 坐标，单位米
     * @param secondEci 第二端点（目标或卫星）的 TEME/ECI 坐标，单位米
     * @return {@code true} 表示线段不被地球椭球遮挡
     */
    public static boolean hasLineOfSight(Vector3 firstEci, Vector3 secondEci) {
        Vector3 segment = secondEci.subtract(firstEci);
        // q = dx²/a² + dy²/a² + dz²/c²，即方向向量在椭球度量下的平方长度
        double quadratic = (segment.getX() * segment.getX() + segment.getY() * segment.getY())
                / (EARTH_EQUATORIAL_RADIUS_M * EARTH_EQUATORIAL_RADIUS_M)
                + segment.getZ() * segment.getZ() / earthPolarRadiusSquared();
        if (quadratic == 0.0) {
            return false;
        }
        // l = 2·(x0·dx/a² + y0·dy/a² + z0·dz/c²)
        double linear = 2.0 * ((firstEci.getX() * segment.getX() + firstEci.getY() * segment.getY())
                / (EARTH_EQUATORIAL_RADIUS_M * EARTH_EQUATORIAL_RADIUS_M)
                + firstEci.getZ() * segment.getZ() / earthPolarRadiusSquared());
        // c0 = x0²/a² + y0²/a² + z0²/c² − 1：起点相对椭球表面的归一化高度项
        double constant = (firstEci.getX() * firstEci.getX() + firstEci.getY() * firstEci.getY())
                / (EARTH_EQUATORIAL_RADIUS_M * EARTH_EQUATORIAL_RADIUS_M)
                + firstEci.getZ() * firstEci.getZ() / earthPolarRadiusSquared() - 1.0;
        double discriminant = linear * linear - 4.0 * quadratic * constant;
        if (discriminant < 0.0) {
            return true;
        }
        double root = Math.sqrt(Math.max(0.0, discriminant));
        double firstIntersection = (-linear - root) / (2.0 * quadratic);
        double secondIntersection = (-linear + root) / (2.0 * quadratic);
        // 起点或终点落在地球内时，卫星—目标连线不可能视通；端点刚好落在椭球面上则沿用
        // 原有开区间规则，避免数值边界把贴地场景误判为遮挡。
        if (isInsideWgs84Ellipsoid(firstEci) || isInsideWgs84Ellipsoid(secondEci)) {
            return false;
        }
        return !isInsideOpenSegment(firstIntersection) && !isInsideOpenSegment(secondIntersection);
    }

    /** 判断参数根是否位于卫星—目标线段的严格内部。 */
    private static boolean isInsideOpenSegment(double fraction) {
        return fraction > LINE_OF_SIGHT_ENDPOINT_TOLERANCE
                && fraction < 1.0 - LINE_OF_SIGHT_ENDPOINT_TOLERANCE;
    }

    /** WGS84 椭球极半径平方，按需计算避免复制一套独立常量。 */
    private static double earthPolarRadiusSquared() {
        double polarRadius = EARTH_EQUATORIAL_RADIUS_M * (1.0 - EARTH_FLATTENING);
        return polarRadius * polarRadius;
    }

    /** 判断点是否严格位于 WGS84 椭球内部。 */
    private static boolean isInsideWgs84Ellipsoid(Vector3 point) {
        return (point.getX() * point.getX() + point.getY() * point.getY())
                / (EARTH_EQUATORIAL_RADIUS_M * EARTH_EQUATORIAL_RADIUS_M)
                + point.getZ() * point.getZ() / earthPolarRadiusSquared()
                < 1.0 - ELLIPSOID_SURFACE_TOLERANCE;
    }

}
