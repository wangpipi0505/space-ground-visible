package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class DownlinkWindowValidatorTest {

    @Test
    void requiresOneStationWindowToCoverEntireCustomPeriod() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);
        SpacePlanningModels.DownlinkPlanResponse plan = new SpacePlanningModels.DownlinkPlanResponse();
        plan.getSatelliteStationWindows().add(new SpacePlanningModels.SatelliteStationWindowResponse(
                1L, "ST-001", Collections.singletonList(new SpacePlanningModels.WindowResponse(
                        start.minusSeconds(1), end.plusSeconds(1)))));

        assertThat(DownlinkWindowValidator.isSatisfied(plan, start, end)).isTrue();
    }

    @Test
    void rejectsPeriodWhenOnlyPartOfItCanLink() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);
        SpacePlanningModels.DownlinkPlanResponse plan = new SpacePlanningModels.DownlinkPlanResponse();
        plan.getSatelliteStationWindows().add(new SpacePlanningModels.SatelliteStationWindowResponse(
                1L, "ST-001", Collections.singletonList(new SpacePlanningModels.WindowResponse(
                        start, end.minusSeconds(1)))));

        assertThat(DownlinkWindowValidator.isSatisfied(plan, start, end)).isFalse();
    }

    /**
     * 两条不同的站星链路在同一时刻无缝衔接时，任务时段应由合并覆盖窗口通过校验。
     * 这覆盖多星接力下传，而不是把任一单链路误当成唯一可选窗口。
     */
    @Test
    void acceptsCustomPeriodCoveredByTouchingWindowsFromDifferentLinks() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant handover = start.plusSeconds(10);
        Instant end = handover.plusSeconds(10);
        SpacePlanningModels.DownlinkPlanResponse plan = new SpacePlanningModels.DownlinkPlanResponse();
        plan.getSatelliteStationWindows().add(new SpacePlanningModels.SatelliteStationWindowResponse(
                1L, "ST-001", Collections.singletonList(new SpacePlanningModels.WindowResponse(start, handover))));
        plan.getSatelliteStationWindows().add(new SpacePlanningModels.SatelliteStationWindowResponse(
                2L, "ST-002", Collections.singletonList(new SpacePlanningModels.WindowResponse(handover, end))));

        assertThat(DownlinkWindowValidator.isSatisfied(plan, start, end)).isTrue();
    }
}
