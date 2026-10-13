package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class KeplerOrbitStateProviderTest {

    /** 历元时刻零角度圆轨道的输出应与 EME2000 位置经 Orekit 转换后的 TEME 位置一致（坐标系统一约定）。 */
    @Test
    void returnsSemiMajorAxisDistanceAtCircularOrbitEpoch() {
        Instant epoch = Instant.parse("2026-01-01T00:00:00Z");
        KeplerOrbitStateProvider provider = new KeplerOrbitStateProvider(
                7_000_000.0, 0.0, 0.0, 0.0, 0.0, 0.0, epoch);

        Vector3 position = provider.positionAt(epoch);

        assertThat(position.norm()).isCloseTo(7_000_000.0, offset(1.0e-3));
        Vector3D expectedTeme = FramesFactory.getEME2000()
                .getTransformTo(FramesFactory.getTEME(), new AbsoluteDate(epoch, TimeScalesFactory.getUTC()))
                .transformPosition(new Vector3D(7_000_000.0, 0.0, 0.0));
        assertThat(position.getX()).isCloseTo(expectedTeme.getX(), offset(1.0e-3));
        assertThat(position.getY()).isCloseTo(expectedTeme.getY(), offset(1.0e-3));
        assertThat(position.getZ()).isCloseTo(expectedTeme.getZ(), offset(1.0e-3));
    }

    /** 验证六根数外推包含 J2 摄动，而不是继续退化为纯二体开普勒模型。对照位置同样转换到 TEME 系。 */
    @Test
    void appliesJ2PerturbationBeyondThePureTwoBodyPosition() {
        Instant epoch = Instant.parse("2026-01-01T00:00:00Z");
        KeplerOrbitStateProvider provider = new KeplerOrbitStateProvider(
                7_000_000.0, 0.01, 45.0, 20.0, 10.0, 30.0, epoch);

        Instant oneDayLater = epoch.plusSeconds(86_400);
        Vector3 numericalPosition = provider.positionAt(oneDayLater);
        Vector3 pureTwoBodyPosition = pureTwoBodyPositionAt(oneDayLater, epoch,
                7_000_000.0, 0.01, 45.0, 20.0, 10.0, 30.0);

        double differenceM = numericalPosition.subtract(pureTwoBodyPosition).norm();
        // 两个位置同在 TEME 系，差异即纯 J2 摄动项（1 天量级为数百至上千公里）；TEME 输出约定由历元时刻用例保证
        assertThat(differenceM).isGreaterThan(1_000.0);
    }

    /** 验证多次按不同顺序查询同一时刻时，数值传播始终以六根数历元为基准。 */
    @Test
    void returnsTheSamePositionRegardlessOfPreviousQueryOrder() {
        Instant epoch = Instant.parse("2026-01-01T00:00:00Z");
        KeplerOrbitStateProvider expectedProvider = new KeplerOrbitStateProvider(
                8_000_000.0, 0.1, 45.0, 20.0, 10.0, 30.0, epoch, 10.0);
        KeplerOrbitStateProvider queriedProvider = new KeplerOrbitStateProvider(
                8_000_000.0, 0.1, 45.0, 20.0, 10.0, 30.0, epoch, 10.0);

        Instant expectedInstant = epoch.plusSeconds(3_601).plusMillis(300);
        Vector3 expected = expectedProvider.positionAt(expectedInstant);
        queriedProvider.positionAt(epoch.plusSeconds(86_395).plusMillis(700));
        Vector3 actual = queriedProvider.positionAt(expectedInstant);

        assertThat(actual.subtract(expected).norm()).isLessThan(0.001);
    }

    /** 使用独立的纯二体公式生成对照位置（不含任何 J2 摄动），并转换到 TEME 系与数值结果对齐。 */
    private Vector3 pureTwoBodyPositionAt(Instant instant,
                                          Instant epoch,
                                          double semiMajorAxisM,
                                          double eccentricity,
                                          double inclinationDeg,
                                          double raanDeg,
                                          double argumentOfPerigeeDeg,
                                          double meanAnomalyDeg) {
        double elapsedSec = instant.getEpochSecond() - epoch.getEpochSecond()
                + (instant.getNano() - epoch.getNano()) / 1_000_000_000.0;
        double meanMotion = Math.sqrt(3.986004418e14 / Math.pow(semiMajorAxisM, 3));
        double meanAnomaly = Math.toRadians(meanAnomalyDeg) + meanMotion * elapsedSec;
        double eccentricAnomaly = meanAnomaly;
        for (int index = 0; index < 20; index++) {
            eccentricAnomaly -= (eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - meanAnomaly)
                    / (1.0 - eccentricity * Math.cos(eccentricAnomaly));
        }
        double x = semiMajorAxisM * (Math.cos(eccentricAnomaly) - eccentricity);
        double y = semiMajorAxisM * Math.sqrt(1.0 - eccentricity * eccentricity) * Math.sin(eccentricAnomaly);
        double raan = Math.toRadians(raanDeg);
        double inclination = Math.toRadians(inclinationDeg);
        double argument = Math.toRadians(argumentOfPerigeeDeg);
        Vector3D eme2000Position = new Vector3D(
                (Math.cos(raan) * Math.cos(argument) - Math.sin(raan) * Math.sin(argument) * Math.cos(inclination)) * x
                        + (-Math.cos(raan) * Math.sin(argument) - Math.sin(raan) * Math.cos(argument) * Math.cos(inclination)) * y,
                (Math.sin(raan) * Math.cos(argument) + Math.cos(raan) * Math.sin(argument) * Math.cos(inclination)) * x
                        + (-Math.sin(raan) * Math.sin(argument) + Math.cos(raan) * Math.cos(argument) * Math.cos(inclination)) * y,
                Math.sin(argument) * Math.sin(inclination) * x + Math.cos(argument) * Math.sin(inclination) * y);
        Vector3D temePosition = FramesFactory.getEME2000()
                .getTransformTo(FramesFactory.getTEME(), new AbsoluteDate(instant, TimeScalesFactory.getUTC()))
                .transformPosition(eme2000Position);
        return new Vector3(temePosition.getX(), temePosition.getY(), temePosition.getZ());
    }
}
