package com.runisys.orbit.spacevisibility.domain.payload;

/** 两类标准载荷的纯Java校验/归一入口，无HTTP、数据库或Spring依赖。 */
public final class StandardPayloads {
    private StandardPayloads() { }

    /**
     * 标准保存/新请求唯一严格入口：距离已转米，必填数值须有限；可选姿态必须成对。
     * 校验顺序保持原空间保存入口，历史JSON读取仍调用独立归一方法保留缺字段语义。
     * 返回内部有效约束；调用方须另存原0/-1，启动角速度不参与可见性。
     */
    public static PayloadConstraint create(PayloadType payloadType,
            Double minDistanceM, Double maxDistanceM, Double azimuthMinDeg, Double azimuthMaxDeg,
            Double elevationMinDeg, Double elevationMaxDeg, Double angleRateDegPerSec,
            Double sunAvoidHalfAngleDeg, Double startupAngleRateDegPerSec) {
        double minRange = requireFinite(minDistanceM, "最近探测距离");
        double maxRange = requireFinite(maxDistanceM, "最远探测距离");
        double[] azimuth = requireOptionalRange(azimuthMinDeg, azimuthMaxDeg, "方位角范围");
        double[] elevation = requireOptionalRange(elevationMinDeg, elevationMaxDeg, "俯仰角范围");
        double rawAngleRate = requireFinite(angleRateDegPerSec, "跟踪角速度");
        double angleRate = normalizeAngleRate(payloadType, rawAngleRate);
        double sunAvoidHalfAngle = requireFinite(sunAvoidHalfAngleDeg, "太阳回避半角");
        Double startupAngleRate = normalizeStartupAngleRate(validateConfiguredStartupAngleRate(startupAngleRateDegPerSec));
        return createNormalized(payloadType, minRange, maxRange,
                azimuth == null ? null : azimuth[0], azimuth == null ? null : azimuth[1],
                elevation == null ? null : elevation[0], elevation == null ? null : elevation[1],
                angleRate, sunAvoidHalfAngle, startupAngleRate);
    }

    /**
     * 校验可选的姿态窗口。两个端点同时留空才表示不限；只填写一个端点会产生歧义，
     * 因此在接口边界直接拒绝，而不是在几何计算时默默补零。
     */
    public static double[] requireOptionalRange(Double minimum, Double maximum, String parameterName) {
        if (minimum == null && maximum == null) {
            return null;
        }
        return new double[]{requireFinite(minimum, parameterName + "下限"),
                requireFinite(maximum, parameterName + "上限")};
    }

    /** 统一构造两种标准载荷，避免前后端字段看似相同、后端却走不同校验路径。 */
    public static PayloadConstraint createNormalized(PayloadType payloadType,
                                            double minRange,
                                            double maxRange,
                                            Double azimuthMinDeg,
                                            Double azimuthMaxDeg,
                                            Double elevationMinDeg,
                                            Double elevationMaxDeg,
                                            double angleRate,
                                            double sunAvoidHalfAngle,
                                            Double startupAngleRate) {
        if (payloadType == PayloadType.GROUND) {
            return PayloadConstraint.ground(minRange, maxRange, azimuthMinDeg, azimuthMaxDeg,
                    elevationMinDeg, elevationMaxDeg, angleRate, sunAvoidHalfAngle, startupAngleRate);
        }
        return PayloadConstraint.space(minRange, maxRange, azimuthMinDeg, azimuthMaxDeg,
                elevationMinDeg, elevationMaxDeg, angleRate, sunAvoidHalfAngle, startupAngleRate);
    }

    /**
     * 将 WinTool 对空载荷的跟踪角速度标记转换为计算层约束。SPACE 的 0 与 -1
     * 不是零速率，均代表不限制；GROUND 仍按原始非负数值执行。使用最大有限值而非
     * 无穷大，确保后续数值运算和 JSON 处理保持有限数。
     */
    public static double normalizeAngleRate(PayloadType payloadType, Double angleRate) {
        double configuredAngleRate = valueOrZero(angleRate);
        if (!Double.isFinite(configuredAngleRate)) {
            throw new IllegalArgumentException("跟踪角速度必须为有效数值");
        }
        if (payloadType == PayloadType.SPACE
                && (configuredAngleRate == 0.0 || configuredAngleRate == -1.0)) {
            return Double.MAX_VALUE;
        }
        if (configuredAngleRate < 0.0) {
            throw new IllegalArgumentException("跟踪角速度必须为非负有效数值；仅对空载荷可用 -1 表示不限制");
        }
        return configuredAngleRate;
    }

    /** -1 是交付配置的禁用标记；其他负值和非有限值都必须拒绝。 */
    public static Double normalizeStartupAngleRate(Double startupAngleRate) {
        if (startupAngleRate == null || startupAngleRate == -1.0) {
            return null;
        }
        if (!Double.isFinite(startupAngleRate) || startupAngleRate < 0.0) {
            throw new IllegalArgumentException("启动角速度必须为非负有效数值或 -1（禁用）");
        }
        return startupAngleRate;
    }

    /** 校验并保留原始启动角速度标记，供配置界面按文档值回显。 */
    public static Double validateConfiguredStartupAngleRate(Double startupAngleRate) {
        if (startupAngleRate == null) {
            return null;
        }
        if (!Double.isFinite(startupAngleRate) || startupAngleRate < 0.0 && startupAngleRate != -1.0) {
            throw new IllegalArgumentException("启动角速度必须为非负有效数值或 -1（禁用）");
        }
        return startupAngleRate;
    }

    /** 校验数值参数必须存在且有限，取值范围由领域载荷约束统一校验。 */
    public static double requireFinite(Double value, String parameterName) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(parameterName + "必须为有效数值");
        }
        return value;
    }

    /** 对可选数值字段提供标准默认值。 */
    public static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

}
