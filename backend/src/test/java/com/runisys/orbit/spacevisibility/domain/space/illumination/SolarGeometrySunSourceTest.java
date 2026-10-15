package com.runisys.orbit.spacevisibility.domain.space.illumination;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 WinTool 确认的唯一太阳方向来源：JPL DE405 原生星历转换至 TEME。
 * 独立参考值使用正确历元的低精度太阳公式，只用于识别错误历元或错误星历文件。
 */
class SolarGeometrySunSourceTest {

    private static final Instant SAMPLE = Instant.parse("2026-01-18T10:00:00Z");

    @Test
    void temeSunDirectionMatchesIndependentCorrectEpochReference() {
        Vector3 teme = SolarGeometry.sunDirectionTeme(SAMPLE);
        // 独立参考：正确 J2000 历元的低精度太阳黄经（与生产历史缺陷 +10957.5 相反）
        double days = SAMPLE.getEpochSecond() / 86_400.0 - 10_957.5;
        double meanLongitude = normalize(280.460 + 0.9856474 * days);
        double meanAnomaly = normalize(357.528 + 0.9856003 * days);
        double eclipticLongitude = meanLongitude + 1.915 * Math.sin(Math.toRadians(meanAnomaly))
                + 0.020 * Math.sin(Math.toRadians(2.0 * meanAnomaly));
        // Orekit 星历含光行差与章动级差异，容差 0.2 度足够捕获历元换算回归（历史缺陷偏差 >0.5 度）
        assertThat(angleBetween(teme, eclipticLongitudeToUnitVector(eclipticLongitude)))
                .isLessThanOrEqualTo(0.2);
    }

    @Test
    void cachesSunDirectionPerUtcSecond() {
        Vector3 first = SolarGeometry.sunDirectionTeme(SAMPLE);
        Vector3 second = SolarGeometry.sunDirectionTeme(SAMPLE);
        assertThat(second).isSameAs(first);
        // 相邻整秒为不同缓存项；方向差应为太阳周日视运动量级（约 0.004 度/秒）
        Vector3 nextSecond = SolarGeometry.sunDirectionTeme(SAMPLE.plusSeconds(1));
        assertThat(nextSecond).isNotSameAs(first);
        // 太阳周日视运动约 1.1e-5 度/秒；相邻整秒的方向差必须落在该量级
        assertThat(first.angleDeg(nextSecond)).isLessThan(0.001);
    }

    @Test
    void exposesJplDe405AsTheOnlyProductionSunSource() {
        assertThat(SolarGeometry.currentSunSource()).isEqualTo(SolarGeometry.SunSource.JPL_DE405_TEME);
        assertThat(sameDirection(SolarGeometry.sunDirectionEci(SAMPLE),
                SolarGeometry.sunDirectionTeme(SAMPLE))).isTrue();
    }

    /** Vector3 未实现 equals，按分量比较。 */
    private static boolean sameDirection(Vector3 a, Vector3 b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    private static Vector3 eclipticLongitudeToUnitVector(double eclipticLongitudeDeg) {
        double longitudeRad = Math.toRadians(eclipticLongitudeDeg);
        double obliquityRad = Math.toRadians(23.4367);
        return new Vector3(Math.cos(longitudeRad),
                Math.cos(obliquityRad) * Math.sin(longitudeRad),
                Math.sin(obliquityRad) * Math.sin(longitudeRad));
    }

    private static double angleBetween(Vector3 a, Vector3 b) {
        return a.angleDeg(b);
    }

    private static double normalize(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }
}
