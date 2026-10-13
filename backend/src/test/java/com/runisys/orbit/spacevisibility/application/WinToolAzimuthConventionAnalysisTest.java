package com.runisys.orbit.spacevisibility.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.TleOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.IlluminationConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.VisibilitySample;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WinTool 方位/俯仰/距离/角速度口径的系统性拟合分析（数据源：2026-09-17 对方反馈的
 * 19 个 Mission、57 组“星-目标”数据点，覆盖 9/1~9/5 全部合并窗口与 400s/200s 两种门槛）。
 *
 * <p>对方仅 A 星标注“相对-X”且与我方 +180° 换算一致；B/C 星方位相差约 93°/53°。
 * 本分析不再针对单一案例拟合，而是把候选参考系全空间枚举——观测星 LVLH 与目标星
 * LVLH 各自的 +X/-X 参考及正反旋向共 8 种组合——对 57 个数据点逐一比对极值区间，
 * 统计每种口径的命中率，用数据判定 WinTool B/C 星方位的真实参考系。</p>
 */
class WinToolAzimuthConventionAnalysisTest {

    private static final String[] CONVENTION_NAMES = {
            "观测星+X(沿速度)", "观测星-X(相对-X)", "观测星镜像(-方位)", "观测星180-方位",
            "目标星+X(沿速度)", "目标星-X(相对-X)", "目标星镜像(-方位)", "目标星180-方位"};

    @Test
    void fitsEveryDocumentedAzimuthRangeAgainstCandidateReferenceFrames() throws Exception {
        List<JsonNode> scenarios = loadScenarios();
        assertThat(scenarios.size()).isGreaterThanOrEqualTo(19);

        // 命中统计：[方位 8 种口径][A/B/C] → 命中数；以及俯仰/距离/角速度的统一口径命中数。
        int[][] azimuthHits = new int[8][3];
        int[] elevationHits = new int[3];
        int[] distanceHits = new int[3];
        int[] rateHits = new int[3];

        for (JsonNode scenario : scenarios) {
            Instant start = Instant.parse(scenario.get("start").asText());
            Instant end = Instant.parse(scenario.get("end").asText());
            OrbitStateProvider targetOrbit = orbit(scenario.get("tle1").asText(), scenario.get("tle2").asText());

            List<VisibilityObserver> observers = observers();
            List<VisibilityTarget> targets = Collections.singletonList(
                    new VisibilityTarget(scenario.get("name").asText(), targetOrbit));
            VisibilityPlanningService service = new VisibilityPlanningService(Runnable::run, 1);
            List<VisibilityPairResult> pairResults = service.plan(observers, targets, start, end, 60.0, 1.0,
                    IlluminationConstraint.from("ANY", Collections.<String>emptyList(),
                            Collections.<String>emptyList()));

            JsonNode sats = scenario.get("sats");
            String[] letters = {"A", "B", "C"};
            for (int letterIndex = 0; letterIndex < 3; letterIndex++) {
                String letter = letters[letterIndex];
                JsonNode published = sats.get(letter);
                if (published == null) {
                    continue;
                }
                VisibilityPairResult pair = findPair(pairResults, observers.get(letterIndex).getSatelliteId());
                if (pair == null || pair.getArcs().isEmpty()) {
                    continue;
                }
                Range[] azimuthRanges = new Range[8];
                double minElevation = Double.MAX_VALUE;
                double maxElevation = -Double.MAX_VALUE;
                double minDistanceKm = Double.MAX_VALUE;
                double maxDistanceKm = -Double.MAX_VALUE;
                double maxRate = 0.0;
                for (VisibilityArc arc : pair.getArcs()) {
                    for (Instant instant = arc.getStartTime().plusMillis(500);
                         !instant.isAfter(arc.getEndTime()); instant = instant.plusMillis(500)) {
                        Vector3 observerPosition = observers.get(letterIndex).getOrbit().positionAt(instant);
                        Vector3 targetPosition = targetOrbit.positionAt(instant);
                        Vector3 lineOfSight = targetPosition.subtract(observerPosition);
                        double[] observerFrameAzimuth = azimuthUnder(observerFrameBasis(observerPosition,
                                observerOrbitVelocity(observers.get(letterIndex).getOrbit(), instant)), lineOfSight);
                        double[] targetFrameAzimuth = azimuthUnder(targetFrameBasis(targetPosition,
                                targetOrbitVelocity(targetOrbit, instant)), lineOfSight);
                        double[] candidates = {observerFrameAzimuth[0], observerFrameAzimuth[1],
                                360.0 - observerFrameAzimuth[0], 180.0 - observerFrameAzimuth[0],
                                targetFrameAzimuth[0], targetFrameAzimuth[1],
                                360.0 - targetFrameAzimuth[0], 180.0 - targetFrameAzimuth[0]};
                        double elevationDeg = elevationUnder(observerPosition, lineOfSight);
                        double rangeKm = lineOfSight.norm() / 1000.0;
                        minElevation = Math.min(minElevation, elevationDeg);
                        maxElevation = Math.max(maxElevation, elevationDeg);
                        minDistanceKm = Math.min(minDistanceKm, rangeKm);
                        maxDistanceKm = Math.max(maxDistanceKm, rangeKm);
                        for (int c = 0; c < 8; c++) {
                            double value = (candidates[c] % 360.0 + 360.0) % 360.0;
                            if (azimuthRanges[c] == null) {
                                azimuthRanges[c] = new Range(value, value);
                            } else {
                                azimuthRanges[c].min = Math.min(azimuthRanges[c].min, value);
                                azimuthRanges[c].max = Math.max(azimuthRanges[c].max, value);
                            }
                        }
                        // 视线角速度：与生产口径一致，取相邻采样视线夹角/步长。
                        Instant previous = instant.minusMillis(500);
                        Vector3 previousLineOfSight = targetOrbit.positionAt(previous)
                                .subtract(observers.get(letterIndex).getOrbit().positionAt(previous));
                        maxRate = Math.max(maxRate, lineOfSight.angleDeg(previousLineOfSight) / 0.5);
                    }
                }
                double publishedAzMin = normalize360(published.get("az").get(0).asDouble());
                double publishedAzMax = normalize360(published.get("az").get(1).asDouble());
                int matchedConvention = -1;
                for (int c = 0; c < 8; c++) {
                    if (azimuthRanges[c] == null) {
                        continue;
                    }
                    if (circularClose(azimuthRanges[c].min, publishedAzMin)
                            && circularClose(azimuthRanges[c].max, publishedAzMax)) {
                        matchedConvention = c;
                        azimuthHits[c][letterIndex]++;
                        break;
                    }
                }
                if (Math.abs(minElevation + 90.0 - published.get("el").get(0).asDouble()) < 0.3
                        && Math.abs(maxElevation + 90.0 - published.get("el").get(1).asDouble()) < 0.3) {
                    elevationHits[letterIndex]++;
                }
                if (Math.abs(minDistanceKm - published.get("dist").get(0).asDouble()) < 10.0
                        && Math.abs(maxDistanceKm - published.get("dist").get(1).asDouble()) < 10.0) {
                    distanceHits[letterIndex]++;
                }
                if (Math.abs(maxRate - published.get("rate").asDouble()) < 0.005) {
                    rateHits[letterIndex]++;
                }
                StringBuilder row = new StringBuilder(String.format("%-9s %-18s W%s@%ss %s星 对方[%8.4f,%8.4f]",
                        scenario.get("day").asText(), scenario.get("name").asText(),
                        scenario.get("window").asText(), scenario.get("gate").asText(),
                        letter, publishedAzMin, publishedAzMax));
                for (int c = 0; c < 8; c++) {
                    if (azimuthRanges[c] == null) {
                        continue;
                    }
                    boolean match = circularClose(azimuthRanges[c].min, publishedAzMin)
                            && circularClose(azimuthRanges[c].max, publishedAzMax);
                    row.append(String.format(" | %s[%8.4f,%8.4f]%s", shortName(c),
                            azimuthRanges[c].min, azimuthRanges[c].max, match ? "✓" : ""));
                }
                System.out.println("AZ_CONV " + row);
            }
        }

        System.out.println("\n===== 方位口径命中统计（57 数据点按 A/B/C 分列）=====");
        for (int c = 0; c < 8; c++) {
            System.out.printf("%-22s A:%2d/19  B:%2d/19  C:%2d/19%n",
                    CONVENTION_NAMES[c], azimuthHits[c][0], azimuthHits[c][1], azimuthHits[c][2]);
        }
        System.out.printf("俯仰(我方+90°)      A:%2d/19  B:%2d/19  C:%2d/19%n",
                elevationHits[0], elevationHits[1], elevationHits[2]);
        System.out.printf("距离(载荷区间极值)  A:%2d/19  B:%2d/19  C:%2d/19%n",
                distanceHits[0], distanceHits[1], distanceHits[2]);
        System.out.printf("最大相对角速度      A:%2d/19  B:%2d/19  C:%2d/19%n",
                rateHits[0], rateHits[1], rateHits[2]);

        // 已判定口径的回归断言：A 星相对-X（+180°）、B/C 星镜像（360°−方位）、俯仰统一 +90°。
        assertThat(azimuthHits[1][0]).isGreaterThanOrEqualTo(17);
        assertThat(azimuthHits[2][1]).isGreaterThanOrEqualTo(18);
        assertThat(azimuthHits[2][2]).isGreaterThanOrEqualTo(19);
        assertThat(elevationHits[0]).isEqualTo(19);
        assertThat(elevationHits[1]).isEqualTo(19);
        assertThat(elevationHits[2]).isEqualTo(19);
    }

    private static String shortName(int convention) {
        String[] shortNames = {"obs+X", "obs-X", "obs镜像", "obs180-", "tgt+X", "tgt-X", "tgt镜像", "tgt180-"};
        return shortNames[convention];
    }

    private static List<JsonNode> loadScenarios() throws Exception {
        try (InputStream stream = WinToolAzimuthConventionAnalysisTest.class
                .getResourceAsStream("/wintool-azimuth-scenarios.json")) {
            return new ObjectMapper().readValue(stream,
                    new com.fasterxml.jackson.core.type.TypeReference<List<JsonNode>>() { });
        }
    }

    private static VisibilityPairResult findPair(List<VisibilityPairResult> pairResults, String satelliteId) {
        for (VisibilityPairResult pairResult : pairResults) {
            if (pairResult.getObserverSatelliteId().equals(satelliteId)) {
                return pairResult;
            }
        }
        return null;
    }

    private static List<VisibilityObserver> observers() {
        OrbitStateProvider a = orbit(
                "1 54699U 22169A   26222.92719756 -.00000049  00000-0  34238-4 0  9991",
                "2 54699  59.9959  59.5305 0002059 300.6111  59.4721 14.29555684191183");
        OrbitStateProvider b = orbit(
                "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998",
                "2 54700  59.9970  59.5864 0007686 292.5992  67.4230 14.29556166191186");
        OrbitStateProvider c = orbit(
                "1 54214U 22142A   26222.93625795 -.00000058  00000-0  30509-4 0  9992",
                "2 54214  59.9954 119.4031 0017772  18.6395 341.5288 14.29566330197528");
        return Arrays.asList(
                new VisibilityObserver("A", a, PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                        90.0, 270.0, -4.0, 60.0, Double.MAX_VALUE, 90.0, 0.07)),
                new VisibilityObserver("B", b, PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                        null, null, -26.0, 60.0, Double.MAX_VALUE, 45.0, 0.16)),
                new VisibilityObserver("C", c, PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                        null, null, -28.5, 60.0, Double.MAX_VALUE, 60.0, 0.10)));
    }

    /** 观测星 LVLH 基：R 径向、T 沿速度、N=R×T；返回 [T, N] 作为方位参考轴组合。 */
    private static Vector3[] observerFrameBasis(Vector3 observerPosition, Vector3 observerVelocity) {
        Vector3 radial = observerPosition.normalize();
        Vector3 alongTrack = observerVelocity.subtract(radial.scale(observerVelocity.dot(radial))).normalize();
        Vector3 normal = radial.cross(alongTrack);
        return new Vector3[]{alongTrack, normal};
    }

    /** 目标星 LVLH 基：把“星→目标”视线放入目标星自身的 LVLH 参考系。 */
    private static Vector3[] targetFrameBasis(Vector3 targetPosition, Vector3 targetVelocity) {
        Vector3 radial = targetPosition.normalize();
        Vector3 alongTrack = targetVelocity.subtract(radial.scale(targetVelocity.dot(radial))).normalize();
        Vector3 normal = radial.cross(alongTrack);
        return new Vector3[]{alongTrack, normal};
    }

    /** @return [方位(0°=+T, 0~360), 备用: 0°=-T 形式(=方位+180)]。 */
    private static double[] azimuthUnder(Vector3[] basis, Vector3 lineOfSight) {
        Vector3 losUnit = lineOfSight.normalize();
        double azimuth = Math.toDegrees(Math.atan2(losUnit.dot(basis[1]), losUnit.dot(basis[0])));
        if (azimuth < 0.0) {
            azimuth += 360.0;
        }
        double shifted = azimuth + 180.0;
        if (shifted >= 360.0) {
            shifted -= 360.0;
        }
        return new double[]{azimuth, shifted};
    }

    private static double elevationUnder(Vector3 observerPosition, Vector3 lineOfSight) {
        Vector3 radial = observerPosition.normalize();
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0,
                lineOfSight.normalize().dot(radial)))));
    }

    /** 中心差分估计轨道速度（与生产口径一致）。 */
    private static Vector3 targetOrbitVelocity(OrbitStateProvider orbit, Instant instant) {
        return orbit.positionAt(instant.plusSeconds(1))
                .subtract(orbit.positionAt(instant.minusSeconds(1))).scale(0.5);
    }

    private static Vector3 observerOrbitVelocity(OrbitStateProvider orbit, Instant instant) {
        return targetOrbitVelocity(orbit, instant);
    }

    private static double normalize360(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static boolean circularClose(double first, double second) {
        double delta = Math.abs(first - second) % 360.0;
        return Math.min(delta, 360.0 - delta) < 2.0;
    }

    private static OrbitStateProvider orbit(String firstOrbitLine, String secondOrbitLine) {
        return new TleOrbitStateProvider(firstOrbitLine, secondOrbitLine);
    }

    /** 单一口径下方位角的累计极值区间。 */
    private static final class Range {
        private double min;
        private double max;

        private Range(double min, double max) {
            this.min = min;
            this.max = max;
        }
    }
}
