package com.runisys.orbit.spacevisibility.domain.geometry;

/** 空间领域不可变向量；字段与访问器保留原契约，共用标量数学不增加旧弹道适配对象。 */
public final class Vector3 {

    public static final Vector3 ZERO = new Vector3(0.0, 0.0, 0.0);

    private final double x;
    private final double y;
    private final double z;

    public Vector3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    public Vector3 add(Vector3 other) {
        return new Vector3(x + other.x, y + other.y, z + other.z);
    }

    public Vector3 subtract(Vector3 other) {
        return new Vector3(x - other.x, y - other.y, z - other.z);
    }

    public Vector3 scale(double factor) {
        return new Vector3(x * factor, y * factor, z * factor);
    }

    /** 同参考系向量点积委托共用标量主体，保留 x/y/z 的原累加顺序；单位为两输入单位之积。 */
    public double dot(Vector3 other) {
        return GeometryMath.dot(x, y, z, other.x, other.y, other.z);
    }

    public Vector3 cross(Vector3 other) {
        return new Vector3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x);
    }

    /** 共用 sqrt(dot(v,v)) 范数；位置或位置差返回米，零向量返回零，不改变下溢或非有限数行为。 */
    public double norm() {
        return GeometryMath.norm(x, y, z);
    }

    public Vector3 normalize() {
        double norm = norm();
        if (norm == 0.0) {
            throw new IllegalStateException("零向量不能归一化");
        }
        return scale(1.0 / norm);
    }

    /**
     * 同参考系方向夹角（度）：非零乘积走共用弧度数学后转度，零模长乘积仍抛原异常。
     * 极小向量平方下溢也沿用零向量处理；不能套用旧弹道返回 PI 的兼容政策。
     */
    public double angleDeg(Vector3 other) {
        double product = norm() * other.norm();
        if (product == 0.0) {
            throw new IllegalStateException("零向量不能计算夹角");
        }
        return Math.toDegrees(GeometryMath.angleRad(dot(other), product));
    }
}
