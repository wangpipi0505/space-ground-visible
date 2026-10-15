package com.runisys.orbit.spacevisibility.domain.space.visibility;



/**
 * 单个“观测星—目标”组合在本次规划时段内的观测统计：对全部可见采样点累计
 * 距离极值、LVLH 方位/俯仰极值和最大视线角速度。
 *
 * <p>统计口径与 WinTool 的“X 星距离范围 / 方位 / 俯仰 / 最大相对角速度”一致：
 * 极值取自可见采样点（含粗选网格与精选边界），距离单位为公里，方位为 LVLH
 * 0° 沿飞行速度（0~360°），俯仰为 0° 当地水平面（-90~90°）。不可见采样点、
 * 缺失几何量的采样点不参与累计。</p>
 */
public final class VisibilityPairStatistics {

    private long samples;
    private Double minDistanceM;
    private Double maxDistanceM;
    private Double minAzimuthDeg;
    private Double maxAzimuthDeg;
    private Double minElevationDeg;
    private Double maxElevationDeg;
    private double maxAngleRateDegPerSec;

    /** 累计一个可见采样点；几何量缺失的字段自动跳过。 */
    public void accept(VisibilitySample sample) {
        if (sample == null || !sample.isGeometryVisible()) {
            return;
        }
        samples++;
        if (sample.getRangeM() != null) {
            minDistanceM = min(minDistanceM, sample.getRangeM());
            maxDistanceM = max(maxDistanceM, sample.getRangeM());
        }
        if (sample.getAzimuthDeg() != null) {
            minAzimuthDeg = min(minAzimuthDeg, sample.getAzimuthDeg());
            maxAzimuthDeg = max(maxAzimuthDeg, sample.getAzimuthDeg());
        }
        if (sample.getElevationDeg() != null) {
            minElevationDeg = min(minElevationDeg, sample.getElevationDeg());
            maxElevationDeg = max(maxElevationDeg, sample.getElevationDeg());
        }
        maxAngleRateDegPerSec = Math.max(maxAngleRateDegPerSec, sample.getLineOfSightRateDegPerSec());
    }

    /** @return 参与累计的可见采样点数；为 0 时其余字段无意义。 */
    public long getSamples() {
        return samples;
    }

    /** @return 最小观测距离（公里），无可见采样时为 null。 */
    public Double getMinDistanceKm() {
        return toKm(minDistanceM);
    }

    /** @return 最大观测距离（公里），无可见采样时为 null。 */
    public Double getMaxDistanceKm() {
        return toKm(maxDistanceM);
    }

    /** @return 最小 LVLH 方位角（度，0° 沿飞行速度），无可见采样时为 null。 */
    public Double getMinAzimuthDeg() {
        return minAzimuthDeg;
    }

    /** @return 最大 LVLH 方位角（度，0° 沿飞行速度），无可见采样时为 null。 */
    public Double getMaxAzimuthDeg() {
        return maxAzimuthDeg;
    }

    /** @return 最小 LVLH 俯仰角（度，0° 当地水平面），无可见采样时为 null。 */
    public Double getMinElevationDeg() {
        return minElevationDeg;
    }

    /** @return 最大 LVLH 俯仰角（度，0° 当地水平面），无可见采样时为 null。 */
    public Double getMaxElevationDeg() {
        return maxElevationDeg;
    }

    /** @return 可见采样点上的最大视线角速度（度/秒）。 */
    public double getMaxAngleRateDegPerSec() {
        return maxAngleRateDegPerSec;
    }

    /** @return WinTool 口径方位（相对-X 基准，-180~180°）下限：由我方 0° 沿速度方位 + 180° 归一化。 */
    public Double getAzimuthRelativeMinusXMinDeg() {
        return relativeMinusX(minAzimuthDeg, maxAzimuthDeg, true);
    }

    /** @return WinTool 口径方位（相对-X 基准，-180~180°）上限。 */
    public Double getAzimuthRelativeMinusXMaxDeg() {
        return relativeMinusX(minAzimuthDeg, maxAzimuthDeg, false);
    }

    /** @return WinTool 口径俯仰（视线与天底夹角，0~180°）下限：我方水平俯仰 + 90°。 */
    public Double getElevationZenithMinDeg() {
        return minElevationDeg == null ? null : minElevationDeg + 90.0;
    }

    /** @return WinTool 口径俯仰（视线与天底夹角，0~180°）上限：我方水平俯仰 + 90°。 */
    public Double getElevationZenithMaxDeg() {
        return maxElevationDeg == null ? null : maxElevationDeg + 90.0;
    }

    /**
     * 将我方 [min,max] 方位端点各自 +180° 并归一化到 [-180,180)；跨 0°/360° 边界时
     * 换算后可能交换大小，此处按大小重新排列，保证 min ≤ max。
     */
    private static Double relativeMinusX(Double minDeg, Double maxDeg, boolean wantMin) {
        if (minDeg == null || maxDeg == null) {
            return null;
        }
        double first = normalizeRelativeMinusX(minDeg);
        double second = normalizeRelativeMinusX(maxDeg);
        return wantMin ? Math.min(first, second) : Math.max(first, second);
    }

    private static double normalizeRelativeMinusX(double azimuthDeg) {
        double converted = azimuthDeg + 180.0;
        if (converted >= 180.0) {
            converted -= 360.0;
        }
        return converted;
    }

    private static Double toKm(Double meters) {
        return meters == null ? null : meters / 1_000.0;
    }

    private static Double min(Double current, double candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Double max(Double current, double candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }
}
