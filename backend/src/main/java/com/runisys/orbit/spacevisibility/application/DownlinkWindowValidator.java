package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;

import java.time.Instant;
import java.util.List;

public final class DownlinkWindowValidator {

    private DownlinkWindowValidator() {
    }

    /**
     * 判断自选时段是否被一段连续下传覆盖窗口完整覆盖。
     *
     * @param plan 已完成的下传规划结果
     * @param requestedStart 自选时段开始时刻
     * @param requestedEnd 自选时段结束时刻
     * @return 自选时段是否满足全部约束
     */
    public static boolean isSatisfied(SpaceApiModels.DownlinkPlanResponse plan,
                                      Instant requestedStart,
                                      Instant requestedEnd) {
        List<SpaceApiModels.CoverageWindowResponse> coverageWindows = plan.getCoverageWindows().isEmpty()
                ? DownlinkCoverageWindowMerger.merge(plan.getSatelliteStationWindows())
                : plan.getCoverageWindows();
        for (SpaceApiModels.CoverageWindowResponse coverageWindow : coverageWindows) {
            if (!coverageWindow.getStartTime().isAfter(requestedStart)
                    && !coverageWindow.getEndTime().isBefore(requestedEnd)) {
                return true;
            }
        }
        return false;
    }
}
