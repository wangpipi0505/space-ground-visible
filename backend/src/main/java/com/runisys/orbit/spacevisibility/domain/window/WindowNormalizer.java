package com.runisys.orbit.spacevisibility.domain.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 将扫描得到的原始窗口归一为可用于任务选择的稳定连续窗口。
 *
 * <p>处理顺序固定为“按时间排序 → 合并允许断链 → 扣除前后保护”。保护必须在合并后执行，
 * 否则相邻碎片会被分别扣除，导致与 WinTool 的连续建链时长不一致。</p>
 */
public final class WindowNormalizer {

    private WindowNormalizer() {
    }

    /**
     * 对原始窗口进行连续性归并和前后保护；保护后没有正时长的窗口会被剔除。
     *
     * @param source 扫描器产出的原始可见区间
     * @param preGuardSec 建链前保护时长（秒）
     * @param postGuardSec 建链后保护时长（秒）
     * @param allowedGapSec 可视为连续建链的最大断链时长（秒）
     * @return 可直接作为下传覆盖窗口的规范化区间
     */
    public static List<TimeInterval> normalize(List<TimeInterval> source,
                                               double preGuardSec,
                                               double postGuardSec,
                                               double allowedGapSec) {
        validate(preGuardSec, postGuardSec, allowedGapSec);
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<TimeInterval> sorted = new ArrayList<TimeInterval>(source);
        sorted.sort(Comparator.comparingDouble(TimeInterval::getStartSec));
        List<TimeInterval> merged = merge(sorted, allowedGapSec);
        List<TimeInterval> protectedWindows = new ArrayList<TimeInterval>();
        for (TimeInterval interval : merged) {
            double start = interval.getStartSec() + preGuardSec;
            double end = interval.getEndSec() - postGuardSec;
            if (end > start) {
                protectedWindows.add(TimeInterval.of(start, end));
            }
        }
        return protectedWindows;
    }

    /** 输入已经按起点排序；重叠窗口及断链不超过阈值的相邻窗口都归并为一个连续窗口。 */
    private static List<TimeInterval> merge(List<TimeInterval> sorted, double allowedGapSec) {
        List<TimeInterval> result = new ArrayList<TimeInterval>();
        TimeInterval current = sorted.get(0);
        for (int index = 1; index < sorted.size(); index++) {
            TimeInterval next = sorted.get(index);
            if (next.getStartSec() - current.getEndSec() <= allowedGapSec) {
                current = TimeInterval.of(current.getStartSec(), Math.max(current.getEndSec(), next.getEndSec()));
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    private static void validate(double preGuardSec, double postGuardSec, double allowedGapSec) {
        if (preGuardSec < 0.0 || postGuardSec < 0.0 || allowedGapSec < 0.0) {
            throw new IllegalArgumentException("保护时长和允许断链时长不能小于零");
        }
    }
}
