package com.runisys.orbit.spacevisibility.domain.window;

/**
 * 表示在一个连续时间区间内保持不变的非空状态。
 * 状态扫描器使用 null 表示不可见，因此该类型只承载需要输出或后续参与组合的有效状态。
 *
 * @param <T> 状态签名的类型
 */
public final class StateInterval<T> {

    private final TimeInterval interval;
    private final T state;

    public StateInterval(TimeInterval interval, T state) {
        if (interval == null || state == null) {
            throw new IllegalArgumentException("状态区间和状态不能为空");
        }
        this.interval = interval;
        this.state = state;
    }

    /** @return 状态保持不变的相对秒区间。 */
    public TimeInterval getInterval() {
        return interval;
    }

    /** @return 当前区间稳定保持的状态签名。 */
    public T getState() {
        return state;
    }
}
