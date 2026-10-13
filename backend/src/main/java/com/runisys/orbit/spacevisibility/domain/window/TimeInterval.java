package com.runisys.orbit.spacevisibility.domain.window;

import java.util.Objects;

/**
 * 以“相对本次规划起点的秒数”表示的闭区间。
 *
 * <p>窗口边界可以是小数秒；控制器在输出接口时间时才统一转换为 UTC Instant，
 * 因此领域层不丢失精选扫描得到的毫秒级边界。</p>
 */
public final class TimeInterval {

    private final double startSec;
    private final double endSec;

    private TimeInterval(double startSec, double endSec) {
        this.startSec = startSec;
        this.endSec = endSec;
    }

    /** 创建有限且起点不晚于终点的区间；零时长区间可用于扫描边界和相交计算。 */
    public static TimeInterval of(double startSec, double endSec) {
        if (!Double.isFinite(startSec) || !Double.isFinite(endSec) || endSec < startSec) {
            throw new IllegalArgumentException("时间区间起止值无效");
        }
        return new TimeInterval(startSec, endSec);
    }

    public double getStartSec() {
        return startSec;
    }

    public double getEndSec() {
        return endSec;
    }

    public double durationSec() {
        return endSec - startSec;
    }

    public boolean intersects(TimeInterval other) {
        return startSec <= other.endSec && other.startSec <= endSec;
    }

    /** 返回两个闭区间的交集；完全不相交时返回 null，调用方无需构造伪窗口。 */
    public TimeInterval intersection(TimeInterval other) {
        if (!intersects(other)) {
            return null;
        }
        return of(Math.max(startSec, other.startSec), Math.min(endSec, other.endSec));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TimeInterval)) {
            return false;
        }
        TimeInterval interval = (TimeInterval) other;
        return Double.compare(startSec, interval.startSec) == 0
                && Double.compare(endSec, interval.endSec) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(startSec, endSec);
    }

    @Override
    public String toString() {
        return "TimeInterval{" + "startSec=" + startSec + ", endSec=" + endSec + '}';
    }
}
