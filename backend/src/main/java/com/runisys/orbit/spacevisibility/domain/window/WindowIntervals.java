package com.runisys.orbit.spacevisibility.domain.window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * 空间组合和弹道多载荷共用的片段处理主体。保留原片段顺序、交叠和触点，不做时间并集。
 * 时长由原模型提供：空间end-start、弹道V2非负差及旧单锥加1秒的口径不得互换。
 * 无Spring、DTO、传播模型或全局可变状态；每次累计都只拥有本次局部变量。
 */
public final class WindowIntervals {
    private WindowIntervals() { }

    /** 左外右内顺序生成闭区间交集；相切产生零时长片段，无交集不输出。 */
    public static List<TimeInterval> intersect(List<TimeInterval> left, List<TimeInterval> right) {
        List<TimeInterval> result = new ArrayList<TimeInterval>();
        for (TimeInterval first : left) {
            for (TimeInterval second : right) {
                TimeInterval intersection = first.intersection(second);
                if (intersection != null) result.add(intersection);
            }
        }
        return result;
    }

    /**
     * 按原遍历顺序先累计本段点数、再累计本段double时长。不排序、不round、不补偿求和。
     * points为null表示调用方没有点数概念（空间组合）；duration读取器保留原null/NaN策略。
     * 重叠的不同载荷仍分别相加。fallback与最小时长验收由调用方在累计后执行。
     */
    public static <T> Totals total(Iterable<T> segments, ToDoubleFunction<T> duration, ToLongFunction<T> points) {
        long totalPoints = 0L;
        double totalDuration = 0.0;
        for (T segment : segments) {
            if (points != null) totalPoints += points.applyAsLong(segment);
            totalDuration += duration.applyAsDouble(segment);
        }
        return new Totals(totalDuration, totalPoints);
    }

    /** 有序相加的原始结果，不在这里施加具体业务的fallback或取整。 */
    public static final class Totals {
        private final double durationSec;
        private final long pointCount;
        private Totals(double durationSec, long pointCount) {
            this.durationSec = durationSec; this.pointCount = pointCount;
        }
        /** @return 逐段double相加后的秒数，可保留原NaN/Infinity。 */
        public double getDurationSec() { return durationSec; }
        /** @return 逐段long相加后的点数，保留原long运算语义。 */
        public long getPointCount() { return pointCount; }
    }
}
