package com.runisys.orbit.spacevisibility.domain.geometry;

import java.time.Instant;

/**
 * 空间地面几何的TEME↔ECEF位置适配，唯一保留原GMST公式与运算顺序。
 * 不能用于原弹道J2000向量，也不提供未经原实现定义的速度转换。
 * 调用方需保证向量与UTC Instant属于同一物理时刻，位置统一使用米。
 */
public final class TemeEarthFixedFrames {
    private TemeEarthFixedFrames() {
    }

    /** 同一UTC Instant的TEME位置（米）转ECEF，保留原GMST旋转。 */
    public static Vector3 temeToEcef(Vector3 eci, Instant instant) {
        double theta = greenwichMeanSiderealAngleRad(instant);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        return new Vector3(
                cos * eci.getX() + sin * eci.getY(),
                -sin * eci.getX() + cos * eci.getY(),
                eci.getZ());
    }

    /** 同一UTC Instant的ECEF位置（米）转TEME，使用与正向完全相反的GMST旋转。 */
    public static Vector3 ecefToTeme(Vector3 ecef, Instant instant) {
        double theta = greenwichMeanSiderealAngleRad(instant);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        return new Vector3(
                cos * ecef.getX() - sin * ecef.getY(),
                sin * ecef.getX() + cos * ecef.getY(),
                ecef.getZ());
    }

    /** 原UTC近似GMST公式，使用Instant秒及纳秒，忽略DUT1与极移，不改用弹道GAST或Orekit EOP。 */
    private static double greenwichMeanSiderealAngleRad(Instant instant) {
        double julianDate = 2_440_587.5 + instant.getEpochSecond() / 86_400.0
                + instant.getNano() / 86_400_000_000_000.0;
        double centuries = (julianDate - 2_451_545.0) / 36_525.0;
        double degrees = 280.46061837 + 360.98564736629 * (julianDate - 2_451_545.0)
                + 0.000387933 * centuries * centuries
                - centuries * centuries * centuries / 38_710_000.0;
        return Math.toRadians(((degrees % 360.0) + 360.0) % 360.0);
    }
}
