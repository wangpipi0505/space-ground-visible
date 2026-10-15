package com.runisys.orbit.spacevisibility.domain.space.visibility;

import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import com.runisys.orbit.spacevisibility.domain.window.WindowIntervals;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 计算同一目标被多个观测星共同覆盖的时间片。
 *
 * <p>最小时长门槛按同一“目标 + 卫星组合”的全部交集片段累计，而非逐片段过滤；
 * 这与 WinTool 对分段共同可见时间的判定一致，且输出仍保留每个状态分段的边界。</p>
 */
public final class CombinationWindowCalculator {

    private CombinationWindowCalculator() {
    }

    /**
     * 依次求组合成员窗口的交集，并在累计时长达到门槛后返回所有原始交集片段。
     *
     * @param windowsBySatellite 以卫星标识索引的同一目标可见窗口
     * @param constraint 参与卫星顺序及累计最小时长要求
     * @return 合格组合的分段窗口；任意卫星无窗口或累计时长不足时返回空列表
     */
    public static List<CombinationWindow> calculate(Map<String, List<TimeInterval>> windowsBySatellite,
                                                     CombinationConstraint constraint) {
        List<TimeInterval> intersections = null;
        for (String satelliteId : constraint.getSatelliteIds()) {
            List<TimeInterval> windows = windowsBySatellite.get(satelliteId);
            if (windows == null || windows.isEmpty()) {
                return Collections.emptyList();
            }
            intersections = intersections == null
                    ? new ArrayList<TimeInterval>(windows)
                    : WindowIntervals.intersect(intersections, windows);
            if (intersections.isEmpty()) {
                return Collections.emptyList();
            }
        }
        // WinTool 的组合约束以同一目标 + 同一卫星组合的交集累计时长验收。中间发生不可见
        // 间隔时，间隔两侧仍是两个独立的输出片段；只有验收时把它们的时长相加，不能把
        // 时间轴上的空档伪造成一条连续弧段。
        double totalDurationSec = WindowIntervals.total(intersections, TimeInterval::durationSec, null).getDurationSec();
        if (totalDurationSec < constraint.getMinDurationSec()) {
            return Collections.emptyList();
        }
        List<CombinationWindow> result = new ArrayList<CombinationWindow>();
        for (TimeInterval interval : intersections) {
            result.add(new CombinationWindow(constraint.getSatelliteIds(), interval));
        }
        return result;
    }

}
