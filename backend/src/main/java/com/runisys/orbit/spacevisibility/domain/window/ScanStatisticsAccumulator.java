package com.runisys.orbit.spacevisibility.domain.window;

import java.util.concurrent.atomic.LongAdder;

/**
 * 汇总一次规划请求内多个窗口扫描的采样量。
 *
 * <p>单次规划会在多个线程中并行计算“卫星—地面站”或“观测星—目标”组合，因此使用
 * {@link LongAdder} 避免在每次采样回调上争用同一个锁。调用方在所有 future 收集完毕后读取
 * 汇总值并只输出一条 INFO 日志，既保留配置诊断能力，也避免大任务产生逐组合日志洪泛。</p>
 */
public final class ScanStatisticsAccumulator {

    private final LongAdder scanCount = new LongAdder();
    private final LongAdder stateEvaluations = new LongAdder();
    private final LongAdder adaptiveProbeEvaluations = new LongAdder();
    private final LongAdder refinedRangeCount = new LongAdder();

    /**
     * 记录单次扫描统计。空值没有业务含义，直接忽略，便于调用方在兼容旧扫描重载时复用该方法。
     *
     * @param statistics 扫描器对单条链路或组合输出的不可变统计对象
     */
    public void record(TimeWindowScanner.ScanStatistics statistics) {
        if (statistics == null) {
            return;
        }
        scanCount.increment();
        stateEvaluations.add(statistics.getStateEvaluations());
        adaptiveProbeEvaluations.add(statistics.getAdaptiveProbeEvaluations());
        refinedRangeCount.add(statistics.getRefinedRangeCount());
    }

    /** @return 已完成统计回调的扫描次数。 */
    public long getScanCount() {
        return scanCount.sum();
    }

    /** @return 粗选、递归探针与精选合计的状态函数调用次数。 */
    public long getStateEvaluations() {
        return stateEvaluations.sum();
    }

    /** @return 自适应细分实际使用的中间探针次数。 */
    public long getAdaptiveProbeEvaluations() {
        return adaptiveProbeEvaluations.sum();
    }

    /** @return 进入完整精选阶段的粗选候选区间数。 */
    public long getRefinedRangeCount() {
        return refinedRangeCount.sum();
    }
}
