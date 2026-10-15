package com.runisys.orbit.spacevisibility.domain.ballistic;

import com.runisys.orbit.spacevisibility.domain.geometry.GeometryMath;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraintMath;

import static com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator.*;

/**
 * 弹道载荷视场、太阳回避和地球遮挡判定，输入位置单位米、时刻单位J2000秒。
 * 标准载荷使用共享PayloadConstraintMath；圆锥、椭球相切及退出滞回沿用各入口的验收口径。
 * 此类不读取轨迹文件、不扫描偏移，不管理HTTP或结果序列化。
 */
final class BallisticVisibilityGeometry {
    private BallisticVisibilityGeometry() {
    }

    /** 进入视场的角度容差，单位度；只用于抵消浮点边界误差。 */
    private static final double VISIBILITY_ENTER_TOLERANCE_DEG = 1e-9;

    /** 已进入弧段后的退出滞回容差，单位度；用于减少视场边界附近的碎片弧段。 */
    private static final double VISIBILITY_EXIT_HYSTERESIS_DEG = 1e-6;

    static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target, double sensorFovDeg) {
        Vector3 nadir = satellite.scale(-1.0);               // 天底方向 = 卫星指向地心
        Vector3 lineOfSight = target.subtract(satellite);     // 视线矢量 = 目标 - 卫星
        double angle = degrees(angleBetween(nadir, lineOfSight));
        return angle <= sensorFovDeg / 2.0 && hasLineOfSight(satellite, target);
    }

    static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target, double sensorFovDeg,
            double absoluteJ2000Sec) {
        Vector3 nadir = satellite.scale(-1.0);
        Vector3 lineOfSight = target.subtract(satellite);
        double angle = degrees(angleBetween(nadir, lineOfSight));
        return angle <= sensorFovDeg / 2.0
                && hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec);
    }

    static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target,
            SensorVisibilityConfig sensorConfig, double absoluteJ2000Sec, Vector3 sunDirectionEci) {
        return isTargetObservableFromSatellite(
                satellite,
                target,
                normalizeSensorConfig(sensorConfig, 140.0),
                absoluteJ2000Sec,
                sunDirectionEci,
                false);
    }

    static boolean isTargetObservableFromSatelliteStable(Vector3 satellite, Vector3 target, double sensorFovDeg,
            double absoluteJ2000Sec, boolean alreadyInArc) {
        Vector3 nadir = satellite.scale(-1.0);
        Vector3 lineOfSight = target.subtract(satellite);
        double angle = degrees(angleBetween(nadir, lineOfSight));
        double tolerance = alreadyInArc ? VISIBILITY_EXIT_HYSTERESIS_DEG : VISIBILITY_ENTER_TOLERANCE_DEG;
        // 先保留原有视场角判定，再加入极小边界容差；遮挡判断仍使用原 WGS84 椭球线段相交逻辑。
        return angle <= sensorFovDeg / 2.0 + tolerance
                && hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec);
    }

    static boolean isTargetObservableFromSatelliteStable(Vector3 satellite, Vector3 target,
            SensorVisibilityConfig sensorConfig, double absoluteJ2000Sec, boolean alreadyInArc) {
        return isTargetObservableFromSatellite(
                satellite,
                target,
                sensorConfig,
                absoluteJ2000Sec,
                sunDirectionEci(absoluteJ2000Sec),
                alreadyInArc);
    }

    static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target,
            SensorVisibilityConfig sensorConfig, double absoluteJ2000Sec, Vector3 sunDirectionEci,
            boolean alreadyInArc) {
        if (satellite == null || target == null) {
            return false;
        }
        SensorVisibilityConfig config = normalizeSensorConfig(sensorConfig, 140.0);
        if (config.standardConstraint != null) {
            throw new IllegalArgumentException("标准载荷需使用含卫星速度与相邻LOS历史的标准判定入口");
        }
        Vector3 lineOfSight = target.subtract(satellite);
        double range = lineOfSight.norm();
        if (range <= 0.0) {
            return false;
        }
        if (isPositiveFinite(config.maxDetectRangeM) && range > config.maxDetectRangeM) {
            return false;
        }

        Vector3 nadir = satellite.scale(-1.0);
        double angle = degrees(angleBetween(nadir, lineOfSight));
        double tolerance = alreadyInArc ? VISIBILITY_EXIT_HYSTERESIS_DEG : VISIBILITY_ENTER_TOLERANCE_DEG;
        if (!isInPayloadFieldOfView(angle, config, tolerance)) {
            return false;
        }

        // 太阳规避角对所有载荷类型（含对地监视/跟踪）生效，仅当 sunExclusionAngleDeg 设为0才关闭；
        // 若对地载荷按业务不应做太阳规避，会因此漏窗口，需确认是否为设计意图。
        if (isPositiveFinite(config.sunExclusionAngleDeg) && sunDirectionEci != null) {
            double sunAngle = degrees(angleBetween(lineOfSight, sunDirectionEci));
            if (sunAngle < config.sunExclusionAngleDeg - tolerance) {
                return false;
            }
        }
        return hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec);
    }

    /**
     * 按载荷分类做视场判定，switch 天然互斥——单次判定只走一个分支。
     * 多载荷场景下由外层循环为每台载荷传入各自的 config 逐台判定，
     * 因此对空/对地载荷可在同一次计算中分别出窗口（见 resolveSatellitePayloads）。
     * simpleConeHalfAngleDeg 为正时走旧版简单圆锥分支，忽略载荷分类。
     */
    private static boolean isInPayloadFieldOfView(double angleDeg, SensorVisibilityConfig config,
            double toleranceDeg) {
        if (isPositiveFinite(config.simpleConeHalfAngleDeg)) {
            return angleDeg <= config.simpleConeHalfAngleDeg + toleranceDeg;
        }
        PayloadType type = config.payloadType == null ? PayloadType.GROUND_SURVEILLANCE : config.payloadType;
        switch (type) {
            case GROUND_TRACKING:
                return angleDeg <= DEFAULT_GROUND_TRACKING_HALF_ANGLE_DEG + toleranceDeg;
            case AIR_SURVEILLANCE:
                return angleDeg >= DEFAULT_AIR_SURVEILLANCE_INNER_ANGLE_DEG - toleranceDeg
                        && angleDeg <= DEFAULT_AIR_SURVEILLANCE_OUTER_ANGLE_DEG + toleranceDeg;
            case AIR_TRACKING:
                return angleDeg >= DEFAULT_AIR_TRACKING_INNER_ANGLE_DEG - toleranceDeg
                        && angleDeg <= DEFAULT_AIR_TRACKING_OUTER_ANGLE_DEG + toleranceDeg;
            case GROUND_SURVEILLANCE:
            default:
                return angleDeg <= DEFAULT_GROUND_SURVEILLANCE_HALF_ANGLE_DEG + toleranceDeg;
        }
    }

    static boolean isStandardTargetObservable(Vector3 satellite,
            com.runisys.orbit.spacevisibility.domain.geometry.Vector3 velocity, Vector3 target,
            PayloadConstraint constraint, double absoluteJ2000Sec,
            com.runisys.orbit.spacevisibility.domain.geometry.Vector3 previousLos, double elapsedSec) {
        return PayloadConstraintMath.isVisible(toDomainVector(satellite), velocity, toDomainVector(target), constraint,
                previousLos, elapsedSec, hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec),
                toDomainVector(sunDirectionEci(absoluteJ2000Sec)), true);
    }

    static SensorVisibilityConfig normalizeSensorConfig(SensorVisibilityConfig sensorConfig,
            double fallbackSensorFovDeg) {
        SensorVisibilityConfig source = sensorConfig == null
                ? SensorVisibilityConfig.legacySimpleCone(fallbackSensorFovDeg)
                : sensorConfig;
        SensorVisibilityConfig normalized = new SensorVisibilityConfig();
        normalized.standardConstraint = source.standardConstraint;
        normalized.payloadType = source.payloadType == null ? PayloadType.GROUND_SURVEILLANCE : source.payloadType;
        normalized.maxDetectRangeM = isPositiveFinite(source.maxDetectRangeM) ? source.maxDetectRangeM : Double.NaN;
        normalized.sunExclusionAngleDeg = isNonNegativeFinite(source.sunExclusionAngleDeg)
                ? source.sunExclusionAngleDeg
                : DEFAULT_SUN_EXCLUSION_ANGLE_DEG;
        normalized.simpleConeHalfAngleDeg = isPositiveFinite(source.simpleConeHalfAngleDeg)
                ? source.simpleConeHalfAngleDeg
                : Double.NaN;
        return normalized;
    }

    static Vector3 sunDirectionEci(double absoluteJ2000Sec) {
        double jd = 2451545.0 + absoluteJ2000Sec / 86400.0;
        double n = jd - 2451545.0;
        double meanLon = Math.toRadians(normalizeDegrees(280.460 + 0.9856474 * n));
        double meanAnomaly = Math.toRadians(normalizeDegrees(357.528 + 0.9856003 * n));
        double eclipticLon = meanLon
                + Math.toRadians(1.915) * Math.sin(meanAnomaly)
                + Math.toRadians(0.020) * Math.sin(2.0 * meanAnomaly);
        double obliquity = Math.toRadians(23.439 - 0.0000004 * n);
        return new Vector3(
                Math.cos(eclipticLon),
                Math.cos(obliquity) * Math.sin(eclipticLon),
                Math.sin(obliquity) * Math.sin(eclipticLon));
    }

    static boolean isPositiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    static boolean isNonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double normalizeDegrees(double degrees) {
        double value = degrees % 360.0;
        return value < 0.0 ? value + 360.0 : value;
    }

    /** 原单锥球体政策：同系位置单位米，投影端点直接可见、内部相切不可见，不能替换椭球。 */
    private static boolean hasLineOfSight(Vector3 satellite, Vector3 target) {
        return GeometryMath.ballisticSphereLineOfSight(satellite.x, satellite.y, satellite.z,
                target.x, target.y, target.z);
    }

    /**
     * 原弹道椭球适配：绝对旧 J2000 秒先经原岁差/章动/恒星时链转 ECEF，再进入共用求交。
     * 保留相切可见、无额外内部端点排除、1e-9 根容差，不能套用空间 TEME 的历史政策。
     */
    private static boolean hasLineOfSightWgs84Ellipsoid(Vector3 satelliteEci, Vector3 targetEci,
            double absoluteJ2000Sec) {
        Vector3 satellite = eciToEcefPos(satelliteEci, absoluteJ2000Sec);
        Vector3 target = eciToEcefPos(targetEci, absoluteJ2000Sec);
        return GeometryMath.ballisticEllipsoidLineOfSight(satellite.x, satellite.y, satellite.z,
                target.x, target.y, target.z);
    }
    private static double angleBetween(Vector3 a, Vector3 b) {
        double denom = a.norm() * b.norm();
        if (denom == 0.0) {
            return Math.PI;
        }
        return GeometryMath.angleRad(a.dot(b), denom);
    }
}
