package com.runisys.orbit.spacevisibility.domain.geometry;

/**
 * 空间和弹道共用的无状态、纯 JDK 三维数学工具；不感知 DTO、Spring 或时间格式。
 * 调用者必须先保证两个位置属于同一时刻、同一参考系，位置及半径单位均为米。
 * 标量入口避免在旧向量与领域向量之间创建临时对象；保留原乘加顺序和 Math.sqrt，
 * 不用 hypot、FMA、归一化坐标或数值稳定求根替换，以免改变既有逐位回归及边界。
 */
public final class GeometryMath {
    private static final double EQUATORIAL_RADIUS_M = 6378137.0;
    private static final double FLATTENING = 1.0 / 298.257223563;
    private static final double MEAN_RADIUS_M = 6371008.8;

    private GeometryMath() {
    }

    /** 三维点积，按 x、y、z 顺序累加；向量单位由调用者决定，不清洗 NaN 或 Infinity。 */
    public static double dot(double x, double y, double z, double otherX, double otherY, double otherZ) {
        return x * otherX + y * otherY + z * otherZ;
    }

    /** 原欧氏长度 sqrt(dot(v,v))；位置差输入时返回米，极小向量允许平方下溢为零。 */
    public static double norm(double x, double y, double z) {
        return Math.sqrt(dot(x, y, z, x, y, z));
    }

    /** 两点距离：先逐轴 second-first，再按原范数计算；无需分配差向量对象。 */
    public static double distance(double firstX, double firstY, double firstZ,
                                  double secondX, double secondY, double secondZ) {
        return norm(secondX - firstX, secondY - firstY, secondZ - firstZ);
    }

    /**
     * 已求点积和模长乘积的夹角，返回弧度；按原 max(-1,min(1,cos)) 夹紧后 acos。
     * 零乘积政策由两个旧入口各自处理：空间抛异常，弹道返回 PI；本方法不新增校验。
     */
    public static double angleRad(double dotProduct, double normProduct) {
        double cosine = Math.max(-1.0, Math.min(1.0, dotProduct / normProduct));
        return Math.acos(cosine);
    }

    /**
     * 空间历史政策：直接使用原 TEME/ECI 坐标的 WGS84 度量，不追加 ECEF 转换。
     * 相切求根；求根后才排除深于归一化容差 1e-12 的内部端点，根端点容差 1e-10。
     */
    public static boolean spaceLineOfSight(double x, double y, double z, double targetX, double targetY, double targetZ) {
        return ellipsoidLineOfSight(x, y, z, targetX, targetY, targetZ, true);
    }

    /**
     * 弹道椭球历史政策：输入必须已由原 J2000 链旋转到同一时刻 ECEF。
     * 判别式等于零时相切可见，不额外排除内部端点；根端点容差 1e-9。
     * 两端在内部且根均在线段外时仍可见，这是原有行为，不能在数学抽取时修复。
     */
    public static boolean ballisticEllipsoidLineOfSight(double x, double y, double z,
                                                        double targetX, double targetY, double targetZ) {
        return ellipsoidLineOfSight(x, y, z, targetX, targetY, targetZ, false);
    }

    /**
     * 共用椭球系数及原二次求根；布尔量仅表达两种已经存在的政策，不作为通用策略扩展点。
     * 参数化 P(u)=P0+u·d 后，A=d·M·d，B=2P0·M·d，C=P0·M·P0-1。
     * 保留 A==0、判别式、求根、空间内部端点、开区间根的原判断顺序；NaN 沿原比较传播。
     */
    private static boolean ellipsoidLineOfSight(double x, double y, double z,
                                                double targetX, double targetY, double targetZ,
                                                boolean spacePolicy) {
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        double a = EQUATORIAL_RADIUS_M;
        double b = a * (1.0 - FLATTENING);
        double a2 = a * a;
        double b2 = b * b;
        double quadratic = ellipsoidDot(dx, dy, dz, dx, dy, dz, a2, b2);
        if (quadratic == 0.0) {
            return false;
        }
        double linear = 2.0 * ellipsoidDot(x, y, z, dx, dy, dz, a2, b2);
        double constant = ellipsoidDot(x, y, z, x, y, z, a2, b2) - 1.0;
        double discriminant = linear * linear - 4.0 * quadratic * constant;
        if (spacePolicy ? discriminant < 0.0 : discriminant <= 0.0) {
            return true;
        }
        double root = Math.sqrt(spacePolicy ? Math.max(0.0, discriminant) : discriminant);
        double firstIntersection = (-linear - root) / (2.0 * quadratic);
        double secondIntersection = (-linear + root) / (2.0 * quadratic);
        if (spacePolicy && (ellipsoidDot(x, y, z, x, y, z, a2, b2) < 1.0 - 1e-12
                || ellipsoidDot(targetX, targetY, targetZ, targetX, targetY, targetZ, a2, b2) < 1.0 - 1e-12)) {
            return false;
        }
        double tolerance = spacePolicy ? 1e-10 : 1e-9;
        return !insideOpenSegment(firstIntersection, tolerance) && !insideOpenSegment(secondIntersection, tolerance);
    }

    /** 椭球度量内积：x/y 先相加再除赤道半径平方，z 单独除极半径平方，不能改配方顺序。 */
    private static double ellipsoidDot(double x, double y, double z, double u, double v, double w, double a2, double b2) {
        return (x * u + y * v) / a2 + z * w / b2;
    }

    /** 根只在含历史容差的严格开区间内才遮挡；端点和恰好位于容差处均不遮挡。 */
    private static boolean insideOpenSegment(double fraction, double tolerance) {
        return fraction > tolerance && fraction < 1.0 - tolerance;
    }

    /**
     * 弹道单锥原球体政策，输入同一坐标系的两点；不能用 WGS84 椭球代替。
     * 零段不可见；最近点参数位于两端 1e-9 容差内直接可见；内部最近点严格大于
     * 6371008.8 米才可见，因此内部相切不可见。平方、投影、缩放相加及开方顺序保持原样。
     */
    public static boolean ballisticSphereLineOfSight(double x, double y, double z,
                                                     double targetX, double targetY, double targetZ) {
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        double denominator = dot(dx, dy, dz, dx, dy, dz);
        if (denominator == 0.0) {
            return false;
        }
        double u = -dot(x, y, z, dx, dy, dz) / denominator;
        if (u <= 1e-9 || u >= 1.0 - 1e-9) {
            return true;
        }
        return norm(x + dx * u, y + dy * u, z + dz * u) > MEAN_RADIUS_M;
    }
}
