package com.runisys.orbit.spacevisibility.domain.space.geometry;

/**
 * 地面站 WGS84 大地坐标的不可变输入模型。
 * 经度、纬度使用度，高程使用米；几何计算组件负责把该模型转换为 ECEF，不在此处混入坐标系转换逻辑。
 */
public final class GroundStationCoordinate {

    private final double longitudeDeg;
    private final double latitudeDeg;
    private final double heightM;

    /** 创建通过经纬度范围校验的站址；高程允许为负值，以支持海平面以下的真实站址。 */
    public GroundStationCoordinate(double longitudeDeg, double latitudeDeg, double heightM) {
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0
                || latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("地面站经纬度无效");
        }
        this.longitudeDeg = longitudeDeg;
        this.latitudeDeg = latitudeDeg;
        this.heightM = heightM;
    }

    public double getLongitudeDeg() { return longitudeDeg; }
    public double getLatitudeDeg() { return latitudeDeg; }
    public double getHeightM() { return heightM; }
}
