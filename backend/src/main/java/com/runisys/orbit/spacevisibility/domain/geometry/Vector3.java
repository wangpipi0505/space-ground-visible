package com.runisys.orbit.spacevisibility.domain.geometry;

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

    public double dot(Vector3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vector3 cross(Vector3 other) {
        return new Vector3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x);
    }

    public double norm() {
        return Math.sqrt(dot(this));
    }

    public Vector3 normalize() {
        double norm = norm();
        if (norm == 0.0) {
            throw new IllegalStateException("零向量不能归一化");
        }
        return scale(1.0 / norm);
    }

    public double angleDeg(Vector3 other) {
        double product = norm() * other.norm();
        if (product == 0.0) {
            throw new IllegalStateException("零向量不能计算夹角");
        }
        double cosine = Math.max(-1.0, Math.min(1.0, dot(other) / product));
        return Math.toDegrees(Math.acos(cosine));
    }
}
