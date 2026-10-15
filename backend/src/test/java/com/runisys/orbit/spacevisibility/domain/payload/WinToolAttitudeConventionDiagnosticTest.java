package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState;
import com.runisys.orbit.spacevisibility.domain.space.illumination.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.space.visibility.PayloadVisibilityEvaluator;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.space.orbit.TleOrbitStateProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从《空间目标规划结果对比》窗口 4 提取的只读诊断测试。
 *
 * <p>该测试不调用数据库，也不改变规划逻辑。它使用文档所对应的四条 TLE，在 A/B/C 对
 * COSMOS 469 的已知可见时段导出 R（径向）、T（沿迹）、N（轨道法线）分量及当前实现的
 * 方位/俯仰。输出文件用于把 WinTool 的“相对 -X 方位”和“俯仰”与项目坐标约定逐项对应。</p>
 */
class WinToolAttitudeConventionDiagnosticTest {

    private static final Instant[] SAMPLES = {
            Instant.parse("2026-01-18T13:43:41.312Z"),
            Instant.parse("2026-01-18T13:50:00Z"),
            Instant.parse("2026-01-18T13:58:56.749Z")
    };
    private static final Instant DOCUMENT_WINDOW_START = SAMPLES[0];
    private static final Path REPORT = Paths.get("target", "validation", "wintool-attitude-diagnostic.csv");

    private static final OrbitStateProvider TARGET = orbit(
            "1 05721U 71117A   26222.90785164 -.00000083  00000-0  80644-5 0  9995",
            "2 05721  64.4855 205.8841 0066463 263.2903  96.0582 13.76150524744657");

    /** 文档中的 A（试验二十号 A）TLE。 */
    private static final NamedOrbit A = new NamedOrbit("A", orbit(
            "1 54699U 22169A   26222.92719756 -.00000049  00000-0  34238-4 0  9991",
            "2 54699  59.9959  59.5305 0002059 300.6111  59.4721 14.29555684191183"));
    /** 文档中的 B（试验二十号 B）TLE。 */
    private static final NamedOrbit B = new NamedOrbit("B", orbit(
            "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998",
            "2 54700  59.9970  59.5864 0007686 292.5992  67.4230 14.29556166191186"));
    /** 文档中的 C（试验二十号 C）TLE。 */
    private static final NamedOrbit C = new NamedOrbit("C", orbit(
            "1 54214U 22142A   26222.93625795 -.00000058  00000-0  30509-4 0  9992",
            "2 54214  59.9954 119.4031 0017772  18.6395 341.5288 14.29566330197528"));

    /**
     * 导出既有实现的局部姿态分量，作为后续修正的基线。
     *
     * <p>列 `r/t/n` 分别是归一化视线在项目当前 R-T-N 坐标系的投影；`currentElevation`
     * 和 `currentAzimuth` 完全复刻生产代码当前的计算式，因此此测试可以准确判定 A/B
     * 被哪个角度窗口排除，而不是猜测轨道或时间精度问题。</p>
     */
    @Test
    void exportsDocumentWindowFourAttitudeComponents() throws Exception {
        List<String> lines = new java.util.ArrayList<String>();
        lines.add("satellite,time,rangeKm,r,t,n,currentElevationDeg,currentAzimuthDeg,relativeMinusRadialDeg,"
                + "sunSeparationDeg,observerIllumination,targetIllumination");
        for (NamedOrbit observer : Arrays.asList(A, B, C)) {
            for (Instant instant : SAMPLES) {
                Vector3 observerPosition = observer.provider.positionAt(instant);
                Vector3 radial = observerPosition.normalize();
                Vector3 velocity = observer.provider.velocityAt(instant);
                Vector3 alongTrack = velocity.subtract(radial.scale(velocity.dot(radial))).normalize();
                Vector3 normal = radial.cross(alongTrack);
                Vector3 lineOfSight = TARGET.positionAt(instant).subtract(observerPosition);
                Vector3 lineOfSightUnit = lineOfSight.normalize();
                double r = lineOfSightUnit.dot(radial);
                double t = lineOfSightUnit.dot(alongTrack);
                double n = lineOfSightUnit.dot(normal);
                double elevation = Math.toDegrees(Math.asin(clamp(r)));
                double azimuth = normalize360(Math.toDegrees(Math.atan2(n, t)));
                double relativeMinusRadial = Math.toDegrees(Math.acos(clamp(-r)));
                Vector3 sunDirection = SolarGeometry.sunDirectionEci(instant);
                double sunSeparation = lineOfSight.angleDeg(sunDirection);
                IlluminationState observerIllumination = SolarGeometry.illumination(observerPosition, sunDirection);
                IlluminationState targetIllumination = SolarGeometry.illumination(TARGET.positionAt(instant), sunDirection);
                lines.add(String.format(Locale.ROOT,
                        "%s,%s,%.6f,%.9f,%.9f,%.9f,%.6f,%.6f,%.6f,%.6f,%s,%s",
                        observer.name, instant, lineOfSight.norm() / 1_000.0, r, t, n,
                        elevation, azimuth, relativeMinusRadial, sunSeparation,
                        observerIllumination, targetIllumination));
            }
        }
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, lines, StandardCharsets.UTF_8);

        // 诊断样例的距离范围来自 Word 文档；只验证输入 TLE 与数据窗口没有漂移。
        assertThat(lines).hasSize(10);
        assertThat(lines.get(1)).contains("A,2026-01-18T13:43:41.312Z");
    }

    /**
     * 文档窗口 4 的 A 星全程在地球本影内，WinTool 仍给出完整的可见区间。
     * 太阳被地球遮住时不会有太阳直射进入载荷，因此长期太阳规避角不应排除这个采样点。
     */
    @Test
    void acceptsDocumentAObservationWhenTheObserverIsInUmbra() {
        PayloadConstraint constraint = PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                90.0, 270.0, -4.0, 60.0, 0.3, 90.0, 0.07);
        Vector3 observerPosition = A.provider.positionAt(DOCUMENT_WINDOW_START);
        Vector3 targetPosition = TARGET.positionAt(DOCUMENT_WINDOW_START);
        Vector3 previousLineOfSight = TARGET.positionAt(DOCUMENT_WINDOW_START.minusSeconds(1))
                .subtract(A.provider.positionAt(DOCUMENT_WINDOW_START.minusSeconds(1)));

        assertThat(SolarGeometry.illumination(observerPosition, DOCUMENT_WINDOW_START))
                .isEqualTo(IlluminationState.UMBRA);
        assertThat(PayloadVisibilityEvaluator.isVisible(observerPosition, A.provider.velocityAt(DOCUMENT_WINDOW_START),
                targetPosition, constraint, DOCUMENT_WINDOW_START, previousLineOfSight, 1.0)).isTrue();
    }

    private static OrbitStateProvider orbit(String firstLine, String secondLine) {
        return new TleOrbitStateProvider(firstLine, secondLine);
    }

    /** 将 atan2 的负角规范到 [0, 360)，与当前生产实现一致。 */
    private static double normalize360(double degrees) {
        return degrees < 0.0 ? degrees + 360.0 : degrees;
    }

    /** 防止浮点舍入把正弦、余弦值推出反三角函数定义域。 */
    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static final class NamedOrbit {
        private final String name;
        private final OrbitStateProvider provider;

        private NamedOrbit(String name, OrbitStateProvider provider) {
            this.name = name;
            this.provider = provider;
        }
    }
}
