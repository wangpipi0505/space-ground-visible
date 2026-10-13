package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;

/**
 * 标准载荷共用纯数学主体。位置、速度、太阳、LOS必须由调用方保证同一时刻同参考系。
 * 不选择太阳历表、光照或地球模型；各业务显式提供遮挡结果和太阳回避是否生效。
 */
public final class PayloadConstraintMath {
    private PayloadConstraintMath() { }

    /** 距离米，闭边界；最大0表示不限，零距离由调用方在几何入口拒绝。 */
    public static boolean insideRange(double rangeM, PayloadConstraint constraint) {
        return !(rangeM < constraint.getMinDetectRangeM()
                || (constraint.getMaxDetectRangeM() > 0.0 && rangeM > constraint.getMaxDetectRangeM()));
    }

    /** 首点/非正间隔沿用空间零速率约定；其余用实际相邻采样秒数。 */
    public static double lineOfSightRate(Vector3 lineOfSight, Vector3 previous, double elapsedSec) {
        return previous == null || elapsedSec <= 0.0 ? 0.0 : lineOfSight.angleDeg(previous) / elapsedSec;
    }

    /** 首点/非正间隔放行；GROUND零阈值是真零，SPACE sentinel 已在输入归一。 */
    public static boolean insideRate(double rate, Vector3 previous, double elapsedSec, PayloadConstraint constraint) {
        return previous == null || elapsedSec <= 0.0 || rate <= constraint.getAngleRateDegPerSec();
    }

    /** 太阳角等于半角允许；0关闭。空间本影可豁免，弹道始终传true，不能混用政策。 */
    public static boolean outsideSunAvoidance(Vector3 los, Vector3 sunDirection,
            PayloadConstraint constraint, boolean sunAvoidanceApplies) {
        return !(constraint.getSunAvoidHalfAngleDeg() > 0.0 && sunAvoidanceApplies
                && los.angleDeg(sunDirection) < constraint.getSunAvoidHalfAngleDeg());
    }

    /**
     * 在调用方原几何/太阳上下文内判定标准载荷；无圆锥映射或启动角速度判定。
     * 弹道只用此入口，不调用携带空间历表/圆柱影的完整evaluate。
     */
    public static boolean isVisible(Vector3 observer, Vector3 velocity, Vector3 target,
            PayloadConstraint constraint, Vector3 previous, double elapsedSec,
            boolean hasLineOfSight, Vector3 sunDirection, boolean sunAvoidanceApplies) {
        Vector3 los = target.subtract(observer);
        double range = los.norm();
        if (range == 0.0 || !hasLineOfSight || !insideRange(range, constraint)) return false;
        return outsideSunAvoidance(los, sunDirection, constraint, sunAvoidanceApplies)
                && insideAttitudeWindow(lvlhAngles(observer, velocity, los), constraint)
                && insideRate(lineOfSightRate(los, previous, elapsedSec), previous, elapsedSec, constraint);
    }

    /**
     * 判断目标视线是否落入对空载荷的 LVLH 方位/俯仰窗口。
     * 参考系：R 为径向单位向量，T 为速度在垂直 R 平面上的投影单位向量，N = R × T；
     * 俯仰角为视线与当地水平面夹角（正值背向地球），方位角 0 度为沿速度方向。
     * 姿态窗口未配置（方位、俯仰均为 null）时视为不限制。
     */
    public static boolean insideAttitudeWindow(LvlhAngles angles, PayloadConstraint constraint) {
        if (angles == null) {
            // 速度退化（沿径向飞行）时无法构造 LVLH 参考系；此时若配置了姿态窗口只能判不可见。
            return constraint.getAzimuthMinDeg() == null && constraint.getElevationMinDeg() == null;
        }
        if (constraint.getElevationMinDeg() != null) {
            if (angles.elevationDeg < constraint.getElevationMinDeg()
                    || angles.elevationDeg > constraint.getElevationMaxDeg()) {
                return false;
            }
        }
        if (constraint.getAzimuthMinDeg() != null) {
            if (angles.azimuthDeg < constraint.getAzimuthMinDeg()
                    || angles.azimuthDeg > constraint.getAzimuthMaxDeg()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算视线在观测星 LVLH 坐标系下的方位/俯仰角。
     * R 为径向单位向量，T 为速度在垂直 R 平面上的投影单位向量，N = R × T；
     * 俯仰 = asin(视线单位向量·R)，范围 [-90,90]；方位 = atan2(视线·N, 视线·T)，
     * 归一化到 [0,360)。观测星速度与径向平行（退化构型）时返回 null。
     */
    public static LvlhAngles lvlhAngles(Vector3 observer, Vector3 observerVelocity, Vector3 lineOfSight) {
        Vector3 radial = observer.normalize();
        Vector3 alongTrackFlat = observerVelocity.subtract(radial.scale(observerVelocity.dot(radial)));
        if (alongTrackFlat.norm() == 0.0) {
            return null;
        }
        Vector3 alongTrack = alongTrackFlat.normalize();
        Vector3 normal = radial.cross(alongTrack);
        Vector3 losUnit = lineOfSight.normalize();
        double elevationDeg = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, losUnit.dot(radial)))));
        double azimuthDeg = Math.toDegrees(Math.atan2(losUnit.dot(normal), losUnit.dot(alongTrack)));
        if (azimuthDeg < 0.0) {
            azimuthDeg += 360.0;
        }
        return new LvlhAngles(azimuthDeg, elevationDeg);
    }

    /** 一个采样时刻的 LVLH 方位/俯仰角（度）。 */
    public static final class LvlhAngles {
        public final double azimuthDeg;
        public final double elevationDeg;

        private LvlhAngles(double azimuthDeg, double elevationDeg) {
            this.azimuthDeg = azimuthDeg;
            this.elevationDeg = elevationDeg;
        }
    }
}
