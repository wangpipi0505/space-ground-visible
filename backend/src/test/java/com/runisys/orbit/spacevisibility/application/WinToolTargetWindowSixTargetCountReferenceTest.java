package com.runisys.orbit.spacevisibility.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.TleOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.IlluminationConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.window.CombinationConstraint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 《空间目标规划结果对比》弧段 6 的 WinTool 回归基线。
 *
 * <p>使用文档给出的 A/B/C 载荷标记（跟踪角速度为 0、0、-1）及八个目标 TLE，在
 * 2026-01-18T15:27:32.968Z 至 15:44:43.484Z 内以 60 秒粗步长、1 秒精精度、
 * 三星累计共同观测不少于 400 秒计算。该用例同时覆盖两项对齐语义：0/-1 为不限制跟踪
 * 角速度，以及分段共同可见时长累计验收。</p>
 */
class WinToolTargetWindowSixTargetCountReferenceTest {

    private static final Instant START = Instant.parse("2026-01-18T15:27:32.968Z");
    private static final Instant END = Instant.parse("2026-01-18T15:44:43.484Z");
    private static final PayloadConstraintFactory PAYLOADS = new PayloadConstraintFactory(new ObjectMapper());

    private static final OrbitStateProvider A = orbit(
            "1 54699U 22169A   26222.92719756 -.00000049  00000-0  34238-4 0  9991",
            "2 54699  59.9959  59.5305 0002059 300.6111  59.4721 14.29555684191183");
    private static final OrbitStateProvider B = orbit(
            "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998",
            "2 54700  59.9970  59.5864 0007686 292.5992  67.4230 14.29556166191186");
    private static final OrbitStateProvider C = orbit(
            "1 54214U 22142A   26222.93625795 -.00000058  00000-0  30509-4 0  9992",
            "2 54214  59.9954 119.4031 0017772  18.6395 341.5288 14.29566330197528");

    @Test
    void matchesTheEightDocumentTargetsForTheThreeSatelliteConstraint() {
        VisibilityPlanningService service = new VisibilityPlanningService(Runnable::run, 1);
        List<VisibilityPairResult> pairResults = service.plan(observers(), targets(), START, END, 60.0, 1.0,
                IlluminationConstraint.from("ANY", Collections.<String>emptyList(),
                        Collections.<String>emptyList()));

        List<VisibilityObservationResult> results = new VisibilityConstraintPlanner().calculate(START, pairResults,
                Collections.singletonList(new CombinationConstraint(Arrays.asList("A", "B", "C"), 400.0)));

        assertThat(results)
                .extracting(VisibilityObservationResult::getTargetId)
                .containsExactlyInAnyOrder(
                        "SL-8-RB-07426", "SL-8-RB-11136", "COSMOS-1911", "FREGAT-DEB-TANK",
                        "ONEWEB-0153", "ONEWEB-0490", "SDA-1673", "SDA-1680");
    }

    /**
     * 对文档角速度 0/-1 的“关闭限制”解释做对照试验（来自 ab-test 副本的试验记录）：
     * 把 0.3 当作生效限制时仅剩五个目标，ONEWEB 两星与 SL-8-RB-11136 被角速度窗口抹去；
     * 按“0/-1 = 不限制”解释才恢复文档八个目标。窗口六因此锁定了“0/-1 = 不限制”的口径。
     */
    @Test
    void documentsAngleRateInterpretationImpactOnTheDocumentTargetSet() {
        List<VisibilityObserver> restricted = Arrays.asList(
                new VisibilityObserver("A", A, payload("{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                        + "\"distanceUnit\":\"M\",\"azimuth\":[90,270],\"elevation\":[-4,60],"
                        + "\"angleRate\":0.3,\"sunAvoidHalfAngle\":90,\"startupAngleRate\":0.07}")),
                new VisibilityObserver("B", B, payload("{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                        + "\"distanceUnit\":\"M\",\"elevation\":[-26,60],\"angleRate\":0.3,"
                        + "\"sunAvoidHalfAngle\":45,\"startupAngleRate\":-1}")),
                new VisibilityObserver("C", C, payload("{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                        + "\"distanceUnit\":\"M\",\"elevation\":[-28.5,60],\"angleRate\":0.3,"
                        + "\"sunAvoidHalfAngle\":60,\"startupAngleRate\":-1}")));

        System.out.println("WINDOW_SIX_ANGLE_RATE_AB current-0.30 => " + targetIds(plan(restricted)));
        System.out.println("WINDOW_SIX_ANGLE_RATE_AB disabled => " + targetIds(plan(observers())));
    }

    private static List<VisibilityPairResult> plan(List<VisibilityObserver> observers) {
        VisibilityPlanningService service = new VisibilityPlanningService(Runnable::run, 1);
        return service.plan(observers, targets(), START, END, 60.0, 1.0,
                IlluminationConstraint.from("ANY", Collections.<String>emptyList(),
                        Collections.<String>emptyList()));
    }

    private static String targetIds(List<VisibilityPairResult> pairResults) {
        List<VisibilityObservationResult> results = new VisibilityConstraintPlanner().calculate(START, pairResults,
                Collections.singletonList(new CombinationConstraint(Arrays.asList("A", "B", "C"), 400.0)));
        StringBuilder ids = new StringBuilder("[");
        for (VisibilityObservationResult result : results) {
            if (ids.length() > 1) {
                ids.append(", ");
            }
            ids.append(result.getTargetId());
        }
        return ids.append(']').toString();
    }

    private static List<VisibilityObserver> observers() {
        return Arrays.asList(
                new VisibilityObserver("A", A, payload("{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                        + "\"distanceUnit\":\"M\",\"azimuth\":[90,270],\"elevation\":[-4,60],"
                        + "\"angleRate\":0,\"sunAvoidHalfAngle\":90,\"startupAngleRate\":0.07}")),
                new VisibilityObserver("B", B, payload("{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                        + "\"distanceUnit\":\"M\",\"elevation\":[-26,60],\"angleRate\":0,"
                        + "\"sunAvoidHalfAngle\":45,\"startupAngleRate\":-1}")),
                new VisibilityObserver("C", C, payload("{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                        + "\"distanceUnit\":\"M\",\"elevation\":[-28.5,60],\"angleRate\":-1,"
                        + "\"sunAvoidHalfAngle\":60,\"startupAngleRate\":-1}")));
    }

    private static PayloadConstraint payload(String json) {
        return PAYLOADS.parse(json);
    }

    private static List<VisibilityTarget> targets() {
        return Arrays.asList(
                target("SL-8-RB-07426", "1 07426U 74069B   26222.91437301 -.00000052  00000-0 -18309-3 0  9990", "2 07426  74.0537  74.7232 0043858 130.2008 336.9151 12.68871475791042"),
                target("SL-8-RB-11136", "1 11136U 78109J   26222.93353740  .00000028  00000-0  40152-3 0  9990", "2 11136  74.0132  98.3938 0130391  71.8882 289.6302 12.19169947122411"),
                target("COSMOS-1911", "1 18790U 88002C   26222.95025100  .00000025  00000-0  77963-4 0  9992", "2 18790  82.6062 213.5925 0007732  18.5752  41.3085 12.64385895779875"),
                target("FREGAT-DEB-TANK", "1 37347U 11001D   26222.77392765  .00033731  61063-5  86041-3 0  9997", "2 37347  50.3024 333.3686 1189159 168.9958 193.9591 13.19249351663147"),
                target("ONEWEB-0153", "1 48044U 21025C   26222.78657939  .00000111  00000-0  26805-3 0  9995", "2 48044  87.8980 309.3625 0001977  97.4800 262.6554 13.13470485260714"),
                target("ONEWEB-0490", "1 54113U 22138A   26222.72267720 -.00000061  00000-0 -18494-3 0  9995", "2 54113  87.9173 294.1118 0002257  99.2283 260.9105 13.19725727184028"),
                target("SDA-1673", "1 65983U 25230K   26222.89743826  .00000051  00000-0  35886-4 0  9994", "2 65983  81.3033 161.1690 0001858  85.4026 274.7341 13.82264467 41378"),
                target("SDA-1680", "1 65990U 25230S   26222.89542928  .00000143  00000-0  11521-3 0  9993", "2 65990  81.3029 160.8273 0005188  56.6609 303.5044 13.85237612 41399"));
    }

    private static VisibilityTarget target(String targetId, String firstLine, String secondLine) {
        return new VisibilityTarget(targetId, orbit(firstLine, secondLine));
    }

    private static OrbitStateProvider orbit(String firstLine, String secondLine) {
        return new TleOrbitStateProvider(firstLine, secondLine);
    }
}
