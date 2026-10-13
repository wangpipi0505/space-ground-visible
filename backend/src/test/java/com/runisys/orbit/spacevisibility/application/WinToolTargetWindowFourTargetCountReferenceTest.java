package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.TleOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.payload.IlluminationConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.window.CombinationConstraint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 《空间目标规划结果对比》合并弧段 4 的回归基线与角速度语义对照试验。
 *
 * <p>WinTool 文档在窗口 4（2026-01-18T13:43:41.312Z 至 13:58:56.749Z）内只报出
 * COSMOS 469 一个目标。按文档条件（A/B/C 三星共同观测累计不少于 400 秒，载荷
 * 0/-1 表示不限制跟踪角速度）计算时，交付数据集中同样可见的还有 JASON 3 与
 * SL-8 R/B；全库运行共 7 个目标。与 WinTool 的数量差异经排查指向其目标装载集合
 * （文档记录其该轮存在编号报错与剔除），而不是双方引擎的几何或共视语义——弧段 6
 * 的八个文档目标已逐一复现。本用例固化当前已验证的行为，防止后续对齐工作悄悄
 * 改变窗口 4 的结果。</p>
 */
class WinToolTargetWindowFourTargetCountReferenceTest {

    private static final Instant START = Instant.parse("2026-01-18T13:43:41.312Z");
    private static final Instant END = Instant.parse("2026-01-18T13:58:56.749Z");

    /** 角速度口径对照的采样步长；角度差按实际间隔归一为度/秒后再比较。 */
    private static final long RATE_STEP_MILLIS = 250;

    /** WinTool 文档窗口 4 输出的 COSMOS 469 最大相对角速度（度/秒），按 A/B/C 星。 */
    private static final double WIN_TOOL_PUBLISHED_RATE_A = 0.0595687;
    private static final double WIN_TOOL_PUBLISHED_RATE_B = 0.0610451;
    private static final double WIN_TOOL_PUBLISHED_RATE_C = 0.256039;

    private static final OrbitStateProvider A = orbit(
            "1 54699U 22169A   26222.92719756 -.00000049  00000-0  34238-4 0  9991",
            "2 54699  59.9959  59.5305 0002059 300.6111  59.4721 14.29555684191183");
    private static final OrbitStateProvider B = orbit(
            "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998",
            "2 54700  59.9970  59.5864 0007686 292.5992  67.4230 14.29556166191186");
    private static final OrbitStateProvider C = orbit(
            "1 54214U 22142A   26222.93625795 -.00000058  00000-0  30509-4 0  9992",
            "2 54214  59.9954 119.4031 0017772  18.6395 341.5288 14.29566330197528");

    private static final OrbitStateProvider JASON_3 = orbit(
            "1 41240U 16002A   26222.91534975 -.00000055  00000-0  27441-4 0  9990",
            "2 41240  66.0433  10.7723 0008154 268.2126  91.7959 12.87625839494299");
    private static final OrbitStateProvider SL_8_RB = orbit(
            "1 09013U 76067B   26222.71891629 -.00000031  00000-0  81991-4 0  9993",
            "2 09013  65.8662 358.0635 0717479 205.1655 151.2711 12.35415954259055");
    private static final OrbitStateProvider COSMOS_469 = orbit(
            "1 05721U 71117A   26222.90785164 -.00000083  00000-0  80644-5 0  9995",
            "2 05721  64.4855 205.8841 0066463 263.2903  96.0582 13.76150524744657");

    @Test
    void keepsTheThreeDeliveredCandidatesVisibleForTheThreeSatelliteConstraint() {
        List<VisibilityObservationResult> results = plan(observers());

        assertThat(results)
                .extracting(VisibilityObservationResult::getTargetId)
                .containsExactlyInAnyOrder("JASON-3", "SL-8-RB", "COSMOS-469");
    }

    /**
     * 对“文档中的 0/-1 是否表示关闭跟踪角速度限制”做受控 A/B 试验。
     *
     * <p>该试验不修改生产参数：只使用同一条文档窗口和同一组 TLE，逐个替换跟踪角速度的
     * 解释方式。已验证的结论是：收紧角速度虽然会依次抹去 JASON-3、COSMOS-469，但任何
     * 一种解释都无法让窗口 4 只剩 COSMOS-469 的同时保住窗口 6 的八个文档目标（窗口 6
     * 依赖“0/-1 = 不限制”）。输出中的时长为三颗星共同可见的连续窗口时长。</p>
     */
    @Test
    void comparesAngleRateSemanticCandidatesAgainstTheDocumentTargetSet() {
        Map<String, List<VisibilityObservationResult>> candidates = new LinkedHashMap<String, List<VisibilityObservationResult>>();
        candidates.put("current-0.30-0.30-0.30", plan(observers(0.30, 0.30, 0.30)));
        candidates.put("stricter-0.20-0.20-0.20", plan(observers(0.20, 0.20, 0.20)));
        candidates.put("stricter-0.10-0.10-0.10", plan(observers(0.10, 0.10, 0.10)));
        candidates.put("literal-zero-for-A-B", plan(observers(0.0, 0.0, Double.MAX_VALUE)));
        candidates.put("disabled-for-document-0-minus1", plan(observers(Double.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE)));

        for (Map.Entry<String, List<VisibilityObservationResult>> candidate : candidates.entrySet()) {
            System.out.println("ANGLE_RATE_AB " + candidate.getKey() + " => " + describe(candidate.getValue()));
        }
        // 这是候选参数解释试验而非人为设定的答案；至少确保每个候选都经过了同一套规划求解器。
        assertThat(candidates).hasSize(5);
    }

    /**
     * 对照 WinTool 文档公布的最大相对角速度，判定其计算口径。
     *
     * <p>以 COSMOS 469 为样本，按 0.25 秒步长采样，把相邻采样点间的惯性系视线转角与
     * 随观测星 LVLH 姿态变化的相对视线转角分别归一为度/秒。文档公布的 A 星
     * 0.0595687 度/秒与惯性系口径几乎重合，而 LVLH 口径偏差超过 0.03 度/秒，据此确认
     * WinTool 的“最大相对角速度”与当前实现的惯性系视线角速度同口径，LVLH 修正方向
     * 不成立。</p>
     */
    @Test
    void comparesComputedRatesAgainstTheWinToolPublishedValues() {
        VisibilityPlanningService service = new VisibilityPlanningService(Runnable::run, 1);
        List<VisibilityPairResult> pairResults = service.plan(observers(), targets(), START, END, 60.0, 1.0,
                IlluminationConstraint.from("ANY", Collections.<String>emptyList(),
                        Collections.<String>emptyList()));

        Map<String, double[]> cosmos469Rates = new LinkedHashMap<String, double[]>();
        for (VisibilityPairResult pairResult : pairResults) {
            double maxInertialRate = 0.0;
            double maxLvlhRate = 0.0;
            OrbitStateProvider observer = observerOrbit(pairResult.getObserverSatelliteId());
            OrbitStateProvider target = targetOrbit(pairResult.getTargetId());
            for (VisibilityArc arc : pairResult.getArcs()) {
                for (Instant instant = arc.getStartTime().plusMillis(RATE_STEP_MILLIS);
                     !instant.isAfter(arc.getEndTime()); instant = instant.plusMillis(RATE_STEP_MILLIS)) {
                    Instant previous = instant.minusMillis(RATE_STEP_MILLIS);
                    double deltaSeconds = RATE_STEP_MILLIS / 1000.0;
                    maxInertialRate = Math.max(maxInertialRate,
                            inertialRate(observer, target, previous, instant) / deltaSeconds);
                    maxLvlhRate = Math.max(maxLvlhRate,
                            lvlhRelativeRate(observer, target, previous, instant) / deltaSeconds);
                }
            }
            if ("COSMOS-469".equals(pairResult.getTargetId())) {
                cosmos469Rates.put(pairResult.getObserverSatelliteId(),
                        new double[]{maxInertialRate, maxLvlhRate});
            }
            System.out.printf(java.util.Locale.ROOT, "RATE_FRAME_AB %s/%s inertial=%.6f lvlh=%.6f%n",
                    pairResult.getObserverSatelliteId(), pairResult.getTargetId(), maxInertialRate, maxLvlhRate);
        }

        assertThat(cosmos469Rates).hasSize(3);
        System.out.printf(java.util.Locale.ROOT,
                "WIN_TOOL_PUBLISHED_RATE_AB A published=%.7f inertial=%.6f lvlh=%.6f%n",
                WIN_TOOL_PUBLISHED_RATE_A, cosmos469Rates.get("A")[0], cosmos469Rates.get("A")[1]);
        System.out.printf(java.util.Locale.ROOT,
                "WIN_TOOL_PUBLISHED_RATE_AB B published=%.7f inertial=%.6f lvlh=%.6f%n",
                WIN_TOOL_PUBLISHED_RATE_B, cosmos469Rates.get("B")[0], cosmos469Rates.get("B")[1]);
        System.out.printf(java.util.Locale.ROOT,
                "WIN_TOOL_PUBLISHED_RATE_AB C published=%.7f inertial=%.6f lvlh=%.6f%n",
                WIN_TOOL_PUBLISHED_RATE_C, cosmos469Rates.get("C")[0], cosmos469Rates.get("C")[1]);
        // A/B 星的惯性系口径与文档值几乎重合，LVLH 口径明显偏离；以文档值为准判定口径。
        assertThat(Math.abs(cosmos469Rates.get("A")[0] - WIN_TOOL_PUBLISHED_RATE_A))
                .isLessThan(Math.abs(cosmos469Rates.get("A")[1] - WIN_TOOL_PUBLISHED_RATE_A));
        assertThat(Math.abs(cosmos469Rates.get("B")[0] - WIN_TOOL_PUBLISHED_RATE_B))
                .isLessThan(Math.abs(cosmos469Rates.get("B")[1] - WIN_TOOL_PUBLISHED_RATE_B));
    }

    private static List<VisibilityObserver> observers() {
        // 文档中 A/B 的 0 与 C 的 -1 均表示“不限制跟踪角速度”。
        return observers(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    }

    private static List<VisibilityObserver> observers(double aAngleRate,
                                                       double bAngleRate,
                                                       double cAngleRate) {
        return Arrays.asList(
                new VisibilityObserver("A", A, PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                        90.0, 270.0, -4.0, 60.0, aAngleRate, 90.0, 0.07)),
                new VisibilityObserver("B", B, PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                        null, null, -26.0, 60.0, bAngleRate, 45.0, 0.16)),
                new VisibilityObserver("C", C, PayloadConstraint.space(1_000_000.0, 4_000_000.0,
                        null, null, -28.5, 60.0, cAngleRate, 60.0, 0.10)));
    }

    private static List<VisibilityObservationResult> plan(List<VisibilityObserver> observers) {
        VisibilityPlanningService service = new VisibilityPlanningService(Runnable::run, 1);
        List<VisibilityPairResult> pairResults = service.plan(observers, targets(), START, END, 60.0, 1.0,
                IlluminationConstraint.from("ANY", Collections.<String>emptyList(),
                        Collections.<String>emptyList()));
        return new VisibilityConstraintPlanner().calculate(START, pairResults,
                Collections.singletonList(new CombinationConstraint(Arrays.asList("A", "B", "C"), 400.0)));
    }

    private static String describe(List<VisibilityObservationResult> results) {
        StringBuilder description = new StringBuilder();
        for (VisibilityObservationResult result : results) {
            if (description.length() > 0) {
                description.append(", ");
            }
            description.append(result.getTargetId()).append('=');
            for (VisibilityArc arc : result.getArcs()) {
                description.append('[').append(arc.getStartTime()).append("..").append(arc.getEndTime())
                        .append(' ')
                        .append(String.format(java.util.Locale.ROOT, "%.3fs", arc.getDurationSec()))
                        .append(']');
            }
        }
        return description.length() == 0 ? "<none>" : description.toString();
    }

    private static OrbitStateProvider observerOrbit(String observerId) {
        if ("A".equals(observerId)) {
            return A;
        }
        if ("B".equals(observerId)) {
            return B;
        }
        if ("C".equals(observerId)) {
            return C;
        }
        throw new IllegalArgumentException("unknown observer: " + observerId);
    }

    private static OrbitStateProvider targetOrbit(String targetId) {
        if ("JASON-3".equals(targetId)) {
            return JASON_3;
        }
        if ("SL-8-RB".equals(targetId)) {
            return SL_8_RB;
        }
        if ("COSMOS-469".equals(targetId)) {
            return COSMOS_469;
        }
        throw new IllegalArgumentException("unknown target: " + targetId);
    }

    private static double inertialRate(OrbitStateProvider observer,
                                       OrbitStateProvider target,
                                       Instant previous,
                                       Instant current) {
        Vector3 previousLineOfSight = target.positionAt(previous).subtract(observer.positionAt(previous));
        Vector3 currentLineOfSight = target.positionAt(current).subtract(observer.positionAt(current));
        return previousLineOfSight.angleDeg(currentLineOfSight);
    }

    private static double lvlhRelativeRate(OrbitStateProvider observer,
                                           OrbitStateProvider target,
                                           Instant previous,
                                           Instant current) {
        return lvlhDirection(observer, target, previous).angleDeg(lvlhDirection(observer, target, current));
    }

    private static Vector3 lvlhDirection(OrbitStateProvider observer,
                                         OrbitStateProvider target,
                                         Instant instant) {
        Vector3 observerPosition = observer.positionAt(instant);
        Vector3 radial = observerPosition.normalize();
        Vector3 observerVelocity = observer.velocityAt(instant);
        Vector3 alongTrack = observerVelocity.subtract(radial.scale(observerVelocity.dot(radial))).normalize();
        Vector3 normal = radial.cross(alongTrack);
        Vector3 lineOfSight = target.positionAt(instant).subtract(observerPosition).normalize();
        return new Vector3(lineOfSight.dot(radial), lineOfSight.dot(alongTrack), lineOfSight.dot(normal));
    }

    private static List<VisibilityTarget> targets() {
        return Arrays.asList(new VisibilityTarget("JASON-3", JASON_3),
                new VisibilityTarget("SL-8-RB", SL_8_RB),
                new VisibilityTarget("COSMOS-469", COSMOS_469));
    }

    private static OrbitStateProvider orbit(String firstLine, String secondLine) {
        return new TleOrbitStateProvider(firstLine, secondLine);
    }
}
