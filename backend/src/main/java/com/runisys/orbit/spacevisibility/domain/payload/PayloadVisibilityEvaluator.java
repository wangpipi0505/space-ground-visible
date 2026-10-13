package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;

import java.time.Instant;
import static com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraintMath.*;

/**
 * 空间载荷几何/光照联合采样器；复用距离、LVLH角及视线角速度数学，保留DE405太阳。
 * 前一视线来自精选采样，观察星本影按原规则豁免太阳回避；该上下文不同于弹道解析太阳。
 */
public final class PayloadVisibilityEvaluator {

    private PayloadVisibilityEvaluator() {
    }

    /**
     * 按载荷约束判断观测星是否可以稳定观测目标。统一检查地球遮挡、探测距离区间、太阳回避半角和目标阳照；
     * 太阳规避仅在观测星能直接看到太阳（阳照或半影）时生效，本影内太阳已被地球遮挡；
     * 随后仅为历史锥角格式补充天底全锥角；标准对地与对空载荷均继续经过
     * LVLH 方位/俯仰窗口和相邻采样视线角速度约束。
     *
     * @param observer 观测星在 ECI 坐标系的位置
     * @param observerVelocity 观测星在 ECI 坐标系的速度（由位置差分获得）
     * @param target 目标在 ECI 坐标系的位置
     * @param constraint 当前载荷的有效约束
     * @param instant 当前计算时刻
     * @param previousLineOfSight 上一个精选采样时刻的视线向量
     * @param previousSampleElapsedSec 与上一个采样点的时间间隔（秒）
     * @return 当前时刻是否满足全部可见条件
     */
    public static boolean isVisible(Vector3 observer,
                                    Vector3 observerVelocity,
                                    Vector3 target,
                                    PayloadConstraint constraint,
                                    Instant instant,
                                    Vector3 previousLineOfSight,
                                    double previousSampleElapsedSec) {
        return evaluate(observer, observerVelocity, target, constraint, instant, previousLineOfSight,
                previousSampleElapsedSec).isGeometryVisible();
    }

    /**
     * 在同一个采样时刻计算既有几何/载荷可见性与双方光照状态。
     * 太阳方向只计算一次，供太阳规避角和两颗卫星的光照判断共同使用。
     *
     * @param observer 观测星在 ECI 坐标系的位置
     * @param observerVelocity 观测星在 ECI 坐标系的速度（由位置差分获得）
     * @param target 目标在 ECI 坐标系的位置
     * @param constraint 当前对空或对地载荷的长期约束
     * @param instant 当前计算时刻
     * @param previousLineOfSight 上一个精选采样点的视线向量
     * @param previousSampleElapsedSec 与上一个采样点相隔秒数
     * @return 包含几何判定、当前视线角速度和双方精确光照状态的采样快照
     */
    public static VisibilitySample evaluate(Vector3 observer,
                                            Vector3 observerVelocity,
                                            Vector3 target,
                                            PayloadConstraint constraint,
                                            Instant instant,
                                            Vector3 previousLineOfSight,
                                            double previousSampleElapsedSec) {
        return evaluate(observer, observerVelocity, target, constraint, instant, previousLineOfSight,
                previousSampleElapsedSec, SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /**
     * 在同一个采样时刻计算几何/载荷可见性与双方光照状态，并由调用方指定本影判据的太阳视半径。
     * 生产规划传入配置值，旧工具和历史回归仍通过无参重载保持 0.2666° 兼容口径。
     */
    public static VisibilitySample evaluate(Vector3 observer,
                                            Vector3 observerVelocity,
                                            Vector3 target,
                                            PayloadConstraint constraint,
                                            Instant instant,
                                            Vector3 previousLineOfSight,
                                            double previousSampleElapsedSec,
                                            double sunAngularRadiusDeg) {
        Vector3 sunDirection = SolarGeometry.sunDirectionEci(instant);
        IlluminationState observerIllumination = SolarGeometry.illumination(observer, sunDirection, sunAngularRadiusDeg);
        IlluminationState targetIllumination = SolarGeometry.illumination(target, sunDirection, sunAngularRadiusDeg);
        Vector3 lineOfSight = target.subtract(observer);
        double rangeM = lineOfSight.norm();
        double lineOfSightRateDegPerSec = lineOfSightRate(lineOfSight, previousLineOfSight, previousSampleElapsedSec);
        // LVLH 方位/俯仰无条件计算：姿态窗口判定与“距离/方位/俯仰/角速度”统计共用同一套数值，
        // 保证输出统计与判定条件永远出自同一参考系。
        LvlhAngles angles = lvlhAngles(observer, observerVelocity, lineOfSight);
        Double azimuthDeg = angles == null ? null : angles.azimuthDeg;
        Double elevationDeg = angles == null ? null : angles.elevationDeg;
        if (rangeM == 0.0 || !GroundStationGeometry.hasLineOfSight(observer, target)) {
            return new VisibilitySample(false, observerIllumination, targetIllumination,
                    lineOfSightRateDegPerSec, rangeM == 0.0 ? null : rangeM, azimuthDeg, elevationDeg);
        }
        if (!insideRange(rangeM, constraint)) {
            return new VisibilitySample(false, observerIllumination, targetIllumination,
                    lineOfSightRateDegPerSec, rangeM, azimuthDeg, elevationDeg);
        }
        // 太阳规避保护的是传感器免受直射太阳影响，而不是把太阳方向作为永久几何禁区。
        // 当观测星处于本影时，地球已完全遮住太阳盘，视线即使接近地心—太阳方向也不存在
        // 直射风险。若继续用夹角拒绝，会把 WinTool 文档窗口 4 中 A 星整段本影观测错误截断。
        // 半影仍能接收到部分太阳直射，按保守原则继续执行规避；只有 UMBRA 才能豁免。
        if (!outsideSunAvoidance(lineOfSight, sunDirection, constraint,
                observerIllumination != IlluminationState.UMBRA)) {
            return new VisibilitySample(false, observerIllumination, targetIllumination,
                    lineOfSightRateDegPerSec, rangeM, azimuthDeg, elevationDeg);
        }
        // 历史数据没有统一姿态字段，只能沿用旧锥角规则；不能以载荷类型作为分支，
        // 否则新保存的对地参数会被静默忽略。
        if (constraint.isLegacyGroundCone()) {
            return new VisibilitySample(observer.scale(-1.0).angleDeg(lineOfSight)
                    <= constraint.getSensorFullConeAngleDeg() / 2.0, observerIllumination, targetIllumination,
                    lineOfSightRateDegPerSec, rangeM, azimuthDeg, elevationDeg);
        }
        if (!insideAttitudeWindow(angles, constraint)) {
            return new VisibilitySample(false, observerIllumination, targetIllumination,
                    lineOfSightRateDegPerSec, rangeM, azimuthDeg, elevationDeg);
        }
        boolean visible = insideRate(lineOfSightRateDegPerSec, previousLineOfSight, previousSampleElapsedSec, constraint);
        return new VisibilitySample(visible, observerIllumination, targetIllumination,
                lineOfSightRateDegPerSec, rangeM, azimuthDeg, elevationDeg);
    }

}
