package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import java.time.Instant;
import java.util.List;

/**
 * 已计算下传窗口的完整时段覆盖判定；优先使用coverageWindows，无汇总时按原规则合并。
 * 只在某一覆盖窗口含整个请求UTC区间时满足，不把含断链的总包络当连续覆盖。
 */
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
    public static boolean isSatisfied(SpacePlanningModels.DownlinkPlanResponse plan,
                                      Instant requestedStart,
                                      Instant requestedEnd) {
        List<SpacePlanningModels.CoverageWindowResponse> coverageWindows = plan.getCoverageWindows().isEmpty()
                ? DownlinkCoverageWindowMerger.merge(plan.getSatelliteStationWindows())
                : plan.getCoverageWindows();
        for (SpacePlanningModels.CoverageWindowResponse coverageWindow : coverageWindows) {
            if (!coverageWindow.getStartTime().isAfter(requestedStart)
                    && !coverageWindow.getEndTime().isBefore(requestedEnd)) {
                return true;
            }
        }
        return false;
    }
}
