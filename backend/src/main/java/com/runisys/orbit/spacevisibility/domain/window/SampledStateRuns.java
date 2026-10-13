package com.runisys.orbit.spacevisibility.domain.window;

import java.util.Objects;

/**
 * 将调用方已经判定的采样状态串成连续段，不负责取样、插值或时间换算。
 * 空间传入整数纳秒，弹道传入原double秒的rawbits（只存取、不做位值运算）；原生long标记避免
 * 每点时间装箱，也不会把纳秒提前转double。null状态表示段外，equals判断状态连续性。
 * 每次扫描/每个载荷独占一个实例，不跨请求共享；仅关闭非空段时创建不可变快照。
 */
public final class SampledStateRuns<S> {
    private S state;
    private long start;
    private long last;
    private int pointCount;

    /** 初始化当前状态和原位置；非空状态的首点计数为1。 */
    public SampledStateRuns(S initialState, long position) {
        state = initialState;
        start = position;
        last = position;
        pointCount = initialState == null ? 0 : 1;
    }

    /**
     * 接收一个已判定样本。同状态只更新末点及计数；变化时返回旧非空段，随后开启新段。
     * 返回段的last是最后旧状态点；当前position是首个新状态点，空间可用它作切分边界。
     * 不校验标记单调性，避免改写调用方原采样/非法输入语义。
     */
    public Segment<S> accept(S nextState, long position) {
        if (Objects.equals(state, nextState)) {
            last = position;
            if (state != null) pointCount++;
            return null;
        }
        Segment<S> completed = snapshot();
        state = nextState;
        start = position;
        last = position;
        pointCount = nextState == null ? 0 : 1;
        return completed;
    }

    /** 当前非空段快照；由调用方在扫描结束时补尾，不会隐式改变原输出顺序。 */
    public Segment<S> snapshot() {
        return state == null ? null : new Segment<S>(state, start, last, pointCount);
    }

    /** 当前状态供空间粗选比较或弹道退出滞回使用，null代表当前段外。 */
    public S getState() { return state; }

    /** 当前段首标记；空间扫描结束时用总纳秒作尾边界。 */
    public long getStart() { return start; }

    /** 当前段最后样本标记；弹道将rawbits原样还原为double秒。 */
    public long getLast() { return last; }

    /** 当前非空段点数；用于弹道关闭轨迹明细时仍保留metadata。 */
    public int getPointCount() { return pointCount; }

    /** 已关闭段的不可变状态与原位置，时间单位由调用方定义且不混用。 */
    public static final class Segment<S> {
        private final S state;
        private final long start;
        private final long last;
        private final int pointCount;

        private Segment(S state, long start, long last, int pointCount) {
            this.state = state; this.start = start; this.last = last; this.pointCount = pointCount;
        }
        /** @return 该段非空状态。 */
        public S getState() { return state; }
        /** @return 首个同状态样本的原位置标记。 */
        public long getStart() { return start; }
        /** @return 最后同状态样本的原位置标记。 */
        public long getLast() { return last; }
        /** @return 已接受的同状态样本数。 */
        public int getPointCount() { return pointCount; }
    }
}
