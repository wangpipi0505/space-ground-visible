package com.runisys.orbit.spacevisibility.domain.payload;

/**
 * 表示一个计算时刻的几何载荷判定及观测星、目标的精确光照状态。
 * 光照状态即使在几何不可见时也会保留，供上层定位状态边界和诊断结果使用；
 * 视线角速度供弧段起始捕获阈值后处理使用。
 *
 * <p>采样快照同时携带该时刻的距离（米）与视线在观测星 LVLH 坐标系下的方位/俯仰角，
 * 供上层在可见采样点上累计“距离范围 / 方位范围 / 俯仰范围 / 最大相对角速度”统计；
 * 几何不可见时这些几何量可能为 null，使用方必须按 {@link #isGeometryVisible()} 先行判断。</p>
 */
public final class VisibilitySample {

    private final boolean geometryVisible;
    private final IlluminationState observerIllumination;
    private final IlluminationState targetIllumination;
    private final double lineOfSightRateDegPerSec;
    /** 观测星到目标的距离，单位米；几何量缺失时为 null。 */
    private final Double rangeM;
    /** 视线在观测星 LVLH 坐标系下的方位角（度），0° 沿飞行速度方向，范围 [0,360)。 */
    private final Double azimuthDeg;
    /** 视线在观测星 LVLH 坐标系下的俯仰角（度），0° 为当地水平面，正值背向地球，范围 [-90,90]。 */
    private final Double elevationDeg;

    public VisibilitySample(boolean geometryVisible,
                            IlluminationState observerIllumination,
                            IlluminationState targetIllumination) {
        this(geometryVisible, observerIllumination, targetIllumination, 0.0);
    }

    public VisibilitySample(boolean geometryVisible,
                            IlluminationState observerIllumination,
                            IlluminationState targetIllumination,
                            double lineOfSightRateDegPerSec) {
        this(geometryVisible, observerIllumination, targetIllumination, lineOfSightRateDegPerSec,
                null, null, null);
    }

    /** 完整采样快照：几何量仅在视线有效时提供，允许为 null。 */
    public VisibilitySample(boolean geometryVisible,
                            IlluminationState observerIllumination,
                            IlluminationState targetIllumination,
                            double lineOfSightRateDegPerSec,
                            Double rangeM,
                            Double azimuthDeg,
                            Double elevationDeg) {
        if (observerIllumination == null || targetIllumination == null) {
            throw new IllegalArgumentException("观测采样光照状态不能为空");
        }
        this.geometryVisible = geometryVisible;
        this.observerIllumination = observerIllumination;
        this.targetIllumination = targetIllumination;
        this.lineOfSightRateDegPerSec = lineOfSightRateDegPerSec;
        this.rangeM = rangeM;
        this.azimuthDeg = azimuthDeg;
        this.elevationDeg = elevationDeg;
    }

    /** @return 既有地球遮挡、距离、太阳规避、姿态窗口和载荷角速度约束是否同时满足。 */
    public boolean isGeometryVisible() {
        return geometryVisible;
    }

    /** @return 观测卫星的精确光照状态。 */
    public IlluminationState getObserverIllumination() {
        return observerIllumination;
    }

    /** @return 目标卫星的精确光照状态。 */
    public IlluminationState getTargetIllumination() {
        return targetIllumination;
    }

    /** @return 当前采样间隔内的视线角速度（度/秒），无有效前一采样时为 0。 */
    public double getLineOfSightRateDegPerSec() {
        return lineOfSightRateDegPerSec;
    }

    /** @return 观测星到目标的距离（米）；几何量缺失时为 null。 */
    public Double getRangeM() {
        return rangeM;
    }

    /** @return 视线 LVLH 方位角（度，0° 沿飞行速度，[0,360)）；几何量缺失时为 null。 */
    public Double getAzimuthDeg() {
        return azimuthDeg;
    }

    /** @return 视线 LVLH 俯仰角（度，0° 当地水平面，[-90,90]）；几何量缺失时为 null。 */
    public Double getElevationDeg() {
        return elevationDeg;
    }
}
