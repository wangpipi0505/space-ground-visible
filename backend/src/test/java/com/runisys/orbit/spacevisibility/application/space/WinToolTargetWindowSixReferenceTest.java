package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityObserver;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairResult;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityTarget;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.space.orbit.TleOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word 文档“观测窗口 6”的 WinTool 回归用例。
 *
 * <p>文档中 A 星对 ONEWEB-0153 从 15:35:06 持续观测至所选下传窗口结束。
 * 启动角速度 0.07 是 WinTool 可保存但不参与求解的字段；若生产代码将其用作每个
 * 光照状态子弧的重新捕获门槛，结果会在约 15:36:32 被错误截断。</p>
 */
class WinToolTargetWindowSixReferenceTest {

    private static final Instant START = Instant.parse("2026-01-18T15:27:32.968Z");
    private static final Instant END = Instant.parse("2026-01-18T15:44:43.484Z");

    private static final OrbitStateProvider A = new TleOrbitStateProvider(
            "1 54699U 22169A   26222.92719756 -.00000049  00000-0  34238-4 0  9991",
            "2 54699  59.9959  59.5305 0002059 300.6111  59.4721 14.29555684191183");
    private static final OrbitStateProvider ONEWEB_0153 = new TleOrbitStateProvider(
            "1 48044U 21025C   26222.78657939  .00000111  00000-0  26805-3 0  9995",
            "2 48044  87.8980 309.3625 0001977  97.4800 262.6554 13.13470485260714");

    @Test
    void keepsObservationUntilDocumentWindowEndWhenStartupAngleRateIsConfigured() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            VisibilityPlanningService service = new VisibilityPlanningService(executor, 1);
            PayloadConstraint documentPayload = PayloadConstraint.space(
                    1_000_000.0, 4_000_000.0, 90.0, 270.0, -4.0, 60.0,
                    0.3, 90.0, 0.07);

            List<VisibilityPairResult> results = service.plan(
                    Collections.singletonList(new VisibilityObserver("A", A, documentPayload)),
                    Collections.singletonList(new VisibilityTarget("ONEWEB-0153", ONEWEB_0153)),
                    START, END, 60.0, 1.0,
                    IlluminationConstraint.from("ANY", Collections.<String>emptyList(),
                            Collections.<String>emptyList()));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getArcs()).isNotEmpty();
            assertThat(results.get(0).getArcs().get(results.get(0).getArcs().size() - 1).getEndTime())
                    .as("WinTool 文档要求 A 星的可见弧段持续到所选下传窗口结束")
                    .isEqualTo(END);
        } finally {
            executor.shutdownNow();
        }
    }
}
