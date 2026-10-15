package com.runisys.orbit.spacevisibility.domain.geometry;

/**
 * 原弹道J2000与ECEF坐标转换，采用原IAU1976岁差、简化IAU1980章动和GAST。
 * 输入时间为原UTC日历J2000绝对秒（不是相对发射秒或TT），原有offset已包含在输入中，不再扣除。
 * 只承担既有 J2000 坐标变换；不替换空间 TEME/GMST 转换，也不改变太阳源。
 * 位置单位米，速度单位米/秒；真地固导数与STK Fixed惯性分量通过不同方法显式区分。
 */
public final class LegacyJ2000Frames {
    /** 原地球自转率，弧度/秒；固定值保持旧速度口径。 */
    private static final double EARTH_ROTATION_RATE_RAD_S = 7.2921150e-5;
    private static final double ARCSEC_TO_RAD = Math.PI / (180.0 * 3600.0);
    /** 原固定闰秒近似，不使用动态闰秒/EOP数据替代。 */
    private static final double TAI_MINUS_UTC_SECONDS = 37.0;
    private static final double TT_MINUS_UTC_SECONDS = TAI_MINUS_UTC_SECONDS + 32.184;

    private LegacyJ2000Frames() {
    }

    /** 单次时刻的不可变地球定向参数，全部角度为弧度。 */
    private static final class EarthOrientation {
        final double zeta;
        final double theta;
        final double z;
        final double eps0;
        final double dPsi;
        final double dEps;
        final double gast;

        EarthOrientation(double zeta,
                         double theta,
                         double z,
                         double eps0,
                         double dPsi,
                         double dEps,
                         double gast) {
            this.zeta = zeta;
            this.theta = theta;
            this.z = z;
            this.eps0 = eps0;
            this.dPsi = dPsi;
            this.dEps = dEps;
            this.gast = gast;
        }
    }

    /** 原J2000位置（米）到ECEF位置（米）；tSec为原UTC日历J2000绝对秒，按岁差→章动→GAST旋转。 */
    public static Vector3 eciToEcefPos(Vector3 rEci, double tSec) {
        EarthOrientation orientation = earthOrientation(tSec);
        Vector3 v = precessJ2000ToDate(rEci, orientation);
        v = nutateMeanToTrue(v, orientation);
        return rotZ(-orientation.gast, v);
    }

    /** 原J2000速度（米/秒）到真地固坐标导数（米/秒）：在旋转惯性分量上扣Ω×r，不能用于STK Fixed CSV列。 */
    public static Vector3 eciToEcefVel(Vector3 vEci, Vector3 rEci, double tSec) {
        Vector3 rEcef = eciToEcefPos(rEci, tSec);
        EarthOrientation orientation = earthOrientation(tSec);
        Vector3 v = precessJ2000ToDate(vEci, orientation);
        v = nutateMeanToTrue(v, orientation);
        v = rotZ(-orientation.gast, v);
        return new Vector3(
                v.getX() + EARTH_ROTATION_RATE_RAD_S * rEcef.getY(),
                v.getY() - EARTH_ROTATION_RATE_RAD_S * rEcef.getX(),
                v.getZ()
        );
    }

    /** 原J2000惯性速度在ECEF轴上的分量（米/秒），不扣Ω×r；专用于原拟合CSV的STK Fixed速度口径。 */
    public static Vector3 eciToStkFixedVelocity(Vector3 vEci, double tSec) {
        EarthOrientation orientation = earthOrientation(tSec);
        Vector3 v = precessJ2000ToDate(vEci, orientation);
        v = nutateMeanToTrue(v, orientation);
        return rotZ(-orientation.gast, v);
    }

    /** ECEF位置（米）到原J2000位置（米）；tSec为原UTC日历绝对秒，使用原逆序旋转。 */
    public static Vector3 ecefToEciPos(Vector3 rEcef, double tSec) {
        return ecefToEci(rEcef.getX(), rEcef.getY(), rEcef.getZ(), tSec);
    }

    /** 真地固坐标导数（米/秒）先加Ω×r再逆旋转到J2000；不接受STK Fixed惯性速度分量。 */
    public static Vector3 ecefToEciVel(Vector3 vEcef, Vector3 rEcef, double tSec) {
        Vector3 rotatingVelocity = new Vector3(
                vEcef.getX() - EARTH_ROTATION_RATE_RAD_S * rEcef.getY(),
                vEcef.getY() + EARTH_ROTATION_RATE_RAD_S * rEcef.getX(),
                vEcef.getZ()
        );
        EarthOrientation orientation = earthOrientation(tSec);
        Vector3 v = rotZ(orientation.gast, rotatingVelocity);
        v = nutateTrueToMean(v, orientation);
        return precessDateToJ2000(v, orientation);
    }

    /** 把ECEF位置分量按GAST→逆章动→逆岁差返回原J2000，单位米。 */
    private static Vector3 ecefToEci(double xEcef, double yEcef, double zEcef, double tSec) {
        EarthOrientation orientation = earthOrientation(tSec);
        Vector3 v = rotZ(orientation.gast, new Vector3(xEcef, yEcef, zEcef));
        v = nutateTrueToMean(v, orientation);
        return precessDateToJ2000(v, orientation);
    }

    /** IAU1976正向岁差，保留原嵌套旋转及其运算顺序。 */
    private static Vector3 precessJ2000ToDate(Vector3 v, EarthOrientation orientation) {
        return rotZ(orientation.z, rotY(orientation.theta, rotZ(orientation.zeta, v)));
    }

    /** IAU1976逆向岁差，与正向顺序严格相反。 */
    private static Vector3 precessDateToJ2000(Vector3 v, EarthOrientation orientation) {
        return rotZ(-orientation.zeta, rotY(-orientation.theta, rotZ(-orientation.z, v)));
    }

    /** 简化IAU1980主项章动：平赤道到真赤道。 */
    private static Vector3 nutateMeanToTrue(Vector3 v, EarthOrientation orientation) {
        return rotX(
                -(orientation.eps0 + orientation.dEps),
                rotZ(orientation.dPsi, rotX(orientation.eps0, v))
        );
    }

    /** 简化IAU1980主项章动的反向旋转。 */
    private static Vector3 nutateTrueToMean(Vector3 v, EarthOrientation orientation) {
        return rotX(
                -orientation.eps0,
                rotZ(-orientation.dPsi, rotX(orientation.eps0 + orientation.dEps, v))
        );
    }

    /** 原jdUtc→jdTt→IAU1976岁差→简化IAU1980章动→GAST；固定TAI-UTC=37秒，忽略DUT1与EOP，不切换Orekit模型。 */
    private static EarthOrientation earthOrientation(double j2000Sec) {
        double jdUtc = 2451545.0 + j2000Sec / 86400.0;
        double jdTt = jdUtc + TT_MINUS_UTC_SECONDS / 86400.0;
        double tTt = (jdTt - 2451545.0) / 36525.0;

        double zeta = arcsec(2306.2181 * tTt
                + 0.30188 * tTt * tTt
                + 0.017998 * tTt * tTt * tTt);
        double theta = arcsec(2004.3109 * tTt
                - 0.42665 * tTt * tTt
                - 0.041833 * tTt * tTt * tTt);
        double z = arcsec(2306.2181 * tTt
                + 1.09468 * tTt * tTt
                + 0.018203 * tTt * tTt * tTt);

        double eps0 = arcsec(84381.448
                - 46.8150 * tTt
                - 0.00059 * tTt * tTt
                + 0.001813 * tTt * tTt * tTt);
        double omega = Math.toRadians(125.04452
                - 1934.136261 * tTt
                + 0.0020708 * tTt * tTt
                + tTt * tTt * tTt / 450000.0);
        double meanSunLon = Math.toRadians(280.4665 + 36000.7698 * tTt);
        double meanMoonLon = Math.toRadians(218.3165 + 481267.8813 * tTt);
        double dPsi = arcsec(-17.20 * Math.sin(omega)
                - 1.32 * Math.sin(2.0 * meanSunLon)
                - 0.23 * Math.sin(2.0 * meanMoonLon)
                + 0.21 * Math.sin(2.0 * omega));
        double dEps = arcsec(9.20 * Math.cos(omega)
                + 0.57 * Math.cos(2.0 * meanSunLon)
                + 0.10 * Math.cos(2.0 * meanMoonLon)
                - 0.09 * Math.cos(2.0 * omega));

        double gast = gmstIauSeconds(jdUtc) + dPsi * Math.cos(eps0 + dEps);
        return new EarthOrientation(zeta, theta, z, eps0, dPsi, dEps, gast);
    }

    /** 原GMST秒公式，历史形参名为jdUt1但实际传jdUtc；保留忽略DUT1的原近似。 */
    private static double gmstIauSeconds(double jdUt1) {
        double t = (jdUt1 - 2451545.0) / 36525.0;
        double seconds = 67310.54841
                + (876600.0 * 3600.0 + 8640184.812866) * t
                + 0.093104 * t * t
                - 6.2e-6 * t * t * t;
        seconds = seconds % 86400.0;
        if (seconds < 0.0) {
            seconds += 86400.0;
        }
        return seconds * Math.PI / 43200.0;
    }

    /** 绕X轴的原旋转约定，保留乘加顺序。 */
    private static Vector3 rotX(double angle, Vector3 v) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new Vector3(v.getX(), c * v.getY() + s * v.getZ(), -s * v.getY() + c * v.getZ());
    }

    /** 绕Y轴的原旋转约定，保留乘加顺序。 */
    private static Vector3 rotY(double angle, Vector3 v) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new Vector3(c * v.getX() - s * v.getZ(), v.getY(), s * v.getX() + c * v.getZ());
    }

    /** 绕Z轴的原旋转约定，保留乘加顺序。 */
    private static Vector3 rotZ(double angle, Vector3 v) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new Vector3(c * v.getX() - s * v.getY(), s * v.getX() + c * v.getY(), v.getZ());
    }

    /** 原角秒到弧度的常量乘法。 */
    private static double arcsec(double value) {
        return value * ARCSEC_TO_RAD;
    }
}
