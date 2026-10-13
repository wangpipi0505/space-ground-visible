package com.runisys.orbit.spacevisibility.domain.payload;

/**
 * 表示一颗我方卫星载荷的长期观测约束。
 *
 * <p>新保存的对地、对空载荷都使用相同的距离区间、LVLH 方位/俯仰窗口、
 * 跟踪角速度和太阳回避约束。仅为兼容历史数据库中只有“天底全锥角”的对地配置，
 * 才保留 {@link #legacyGroundCone(double, double, double)} 这一显式旧格式。</p>
 */
public final class PayloadConstraint {

    private final PayloadType payloadType;
    /** 仅历史简化对地载荷为 {@code true}；新格式对地载荷必须参与统一姿态判定。 */
    private final boolean legacyGroundCone;
    private final double sensorFullConeAngleDeg;
    private final double minDetectRangeM;
    private final double maxDetectRangeM;
    private final Double azimuthMinDeg;
    private final Double azimuthMaxDeg;
    private final Double elevationMinDeg;
    private final Double elevationMaxDeg;
    private final double angleRateDegPerSec;
    private final Double startupAngleRateDegPerSec;
    private final double sunAvoidHalfAngleDeg;

    private PayloadConstraint(PayloadType payloadType,
                              boolean legacyGroundCone,
                              double sensorFullConeAngleDeg,
                              double minDetectRangeM,
                              double maxDetectRangeM,
                              Double azimuthMinDeg,
                              Double azimuthMaxDeg,
                              Double elevationMinDeg,
                              Double elevationMaxDeg,
                              double angleRateDegPerSec,
                              Double startupAngleRateDegPerSec,
                              double sunAvoidHalfAngleDeg) {
        if (sensorFullConeAngleDeg < 0.0 || sensorFullConeAngleDeg > 180.0
                || minDetectRangeM < 0.0 || maxDetectRangeM < 0.0
                || (maxDetectRangeM > 0.0 && maxDetectRangeM <= minDetectRangeM)
                || angleRateDegPerSec < 0.0
                || (startupAngleRateDegPerSec != null && startupAngleRateDegPerSec < 0.0)
                || sunAvoidHalfAngleDeg < 0.0 || sunAvoidHalfAngleDeg > 180.0) {
            throw new IllegalArgumentException("载荷参数无效");
        }
        if ((azimuthMinDeg == null) != (azimuthMaxDeg == null)
                || (azimuthMinDeg != null && (azimuthMinDeg < 0.0 || azimuthMaxDeg > 360.0
                || azimuthMinDeg >= azimuthMaxDeg))) {
            throw new IllegalArgumentException("载荷方位角范围无效");
        }
        // 对地目标可处于严格天底方向（-90 度）。使用 -91 作为可配置下界，
        // 与交付文档参数完全一致，同时不改变对空载荷原有的 -90 度边界。
        double minElevationLimit = payloadType == PayloadType.GROUND ? -91.0 : -90.0;
        if ((elevationMinDeg == null) != (elevationMaxDeg == null)
                || (elevationMinDeg != null && (elevationMinDeg < minElevationLimit || elevationMaxDeg > 90.0
                || elevationMinDeg >= elevationMaxDeg))) {
            throw new IllegalArgumentException("载荷俯仰角范围无效");
        }
        this.payloadType = payloadType;
        this.legacyGroundCone = legacyGroundCone;
        this.sensorFullConeAngleDeg = sensorFullConeAngleDeg;
        this.minDetectRangeM = minDetectRangeM;
        this.maxDetectRangeM = maxDetectRangeM;
        this.azimuthMinDeg = azimuthMinDeg;
        this.azimuthMaxDeg = azimuthMaxDeg;
        this.elevationMinDeg = elevationMinDeg;
        this.elevationMaxDeg = elevationMaxDeg;
        this.angleRateDegPerSec = angleRateDegPerSec;
        this.startupAngleRateDegPerSec = startupAngleRateDegPerSec;
        this.sunAvoidHalfAngleDeg = sunAvoidHalfAngleDeg;
    }

    /**
     * 创建历史简化对地载荷约束，保持旧数据的天底指向全锥角逻辑不变。
     *
     * @param sensorFullConeAngleDeg 载荷全锥角（度）
     * @param maxDetectRangeKm 最远探测距离（km），0 表示不限
     * @param sunAvoidHalfAngleDeg 太阳回避半角（度），0 表示不启用
     * @return 对地载荷约束
     */
    public static PayloadConstraint legacyGroundCone(double sensorFullConeAngleDeg,
                                                     double maxDetectRangeKm,
                                                     double sunAvoidHalfAngleDeg) {
        return new PayloadConstraint(PayloadType.GROUND, true, sensorFullConeAngleDeg, 0.0,
                maxDetectRangeKm * 1_000.0, null, null, null, null, 0.0, null, sunAvoidHalfAngleDeg);
    }

    /**
     * 保持二进制和源码兼容的历史入口。新增代码应使用 {@link #legacyGroundCone(double, double, double)}，
     * 使旧锥角规则不会被误用于标准对地载荷。
     */
    @Deprecated
    public static PayloadConstraint ground(double sensorFullConeAngleDeg,
                                           double maxDetectRangeKm,
                                           double sunAvoidHalfAngleDeg) {
        return legacyGroundCone(sensorFullConeAngleDeg, maxDetectRangeKm, sunAvoidHalfAngleDeg);
    }

    /**
     * 创建标准对地载荷约束。字段、单位和判定步骤均与对空载荷一致，距离统一使用米。
     * 对地俯仰下限允许 -91 度，以表达包含严格天底方向的配置。
     */
    public static PayloadConstraint ground(double minDetectRangeM,
                                           double maxDetectRangeM,
                                           Double azimuthMinDeg,
                                           Double azimuthMaxDeg,
                                           Double elevationMinDeg,
                                           Double elevationMaxDeg,
                                           double angleRateDegPerSec,
                                           double sunAvoidHalfAngleDeg,
                                           Double startupAngleRateDegPerSec) {
        return new PayloadConstraint(PayloadType.GROUND, false, 0.0, minDetectRangeM, maxDetectRangeM,
                azimuthMinDeg, azimuthMaxDeg, elevationMinDeg, elevationMaxDeg,
                angleRateDegPerSec, startupAngleRateDegPerSec, sunAvoidHalfAngleDeg);
    }

    /**
     * 创建对空载荷约束。方位、俯仰窗口成对可选，null 表示不启用对应姿态窗口约束；
     * 最远探测距离为 0 表示不限，启动角速度仅作为可空的长期配置元数据保留。
     *
     * @param minDetectRangeM 最近探测距离（米）
     * @param maxDetectRangeM 最远探测距离（米），0 表示不限
     * @param azimuthMinDeg LVLH 方位角下限（度），0 度为沿速度方向
     * @param azimuthMaxDeg LVLH 方位角上限（度）
     * @param elevationMinDeg LVLH 俯仰角下限（度），0 度为当地水平面
     * @param elevationMaxDeg LVLH 俯仰角上限（度）
     * @param angleRateDegPerSec 跟踪角速度上限（度/秒）
     * @param sunAvoidHalfAngleDeg 太阳回避半角（度），0 表示不启用
     * @param startupAngleRateDegPerSec 启动角速度元数据（度/秒），可空表示禁用且当前不参与窗口计算
     * @return 对空载荷约束
     */
    public static PayloadConstraint space(double minDetectRangeM,
                                          double maxDetectRangeM,
                                          Double azimuthMinDeg,
                                          Double azimuthMaxDeg,
                                          Double elevationMinDeg,
                                          Double elevationMaxDeg,
                                          double angleRateDegPerSec,
                                          double sunAvoidHalfAngleDeg,
                                          Double startupAngleRateDegPerSec) {
        return new PayloadConstraint(PayloadType.SPACE, false, 0.0, minDetectRangeM, maxDetectRangeM,
                azimuthMinDeg, azimuthMaxDeg, elevationMinDeg, elevationMaxDeg,
                angleRateDegPerSec, startupAngleRateDegPerSec, sunAvoidHalfAngleDeg);
    }

    public PayloadType getPayloadType() { return payloadType; }
    public boolean isLegacyGroundCone() { return legacyGroundCone; }
    public double getSensorFullConeAngleDeg() { return sensorFullConeAngleDeg; }
    public double getMinDetectRangeM() { return minDetectRangeM; }
    public double getMaxDetectRangeM() { return maxDetectRangeM; }
    public Double getAzimuthMinDeg() { return azimuthMinDeg; }
    public Double getAzimuthMaxDeg() { return azimuthMaxDeg; }
    public Double getElevationMinDeg() { return elevationMinDeg; }
    public Double getElevationMaxDeg() { return elevationMaxDeg; }
    public double getAngleRateDegPerSec() { return angleRateDegPerSec; }
    public Double getStartupAngleRateDegPerSec() { return startupAngleRateDegPerSec; }
    public double getSunAvoidHalfAngleDeg() { return sunAvoidHalfAngleDeg; }
}
