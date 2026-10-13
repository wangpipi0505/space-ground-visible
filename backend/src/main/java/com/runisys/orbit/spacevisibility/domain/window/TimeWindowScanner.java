package com.runisys.orbit.spacevisibility.domain.window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 基于UTC Instant的原空间粗精选状态扫描；相对秒内部保留纳秒转换，递归探测受深度上限约束。
 * 退出边界与最后可见精选点的差别由scan/scanStates既有政策保留，不新增连续事件根求解。
 */
public final class TimeWindowScanner {

    /** 防止错误配置把一个粗选区间膨胀为不可控数量的递归采样点。 */
    private static final int MAX_ADAPTIVE_DEPTH = 16;

    private TimeWindowScanner() {
    }

    /**
     * 使用粗选步长定位状态翻转区间，再使用精选步长逼近可见窗口边界。
     * 谓词由上层提供，使该扫描器可同时服务下传和空间目标可见性计算。
     *
     * @param start 扫描开始时刻
     * @param end 扫描结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒）
     * @param visibleAt 指定时刻是否可见的判定函数
     * @return 相对于开始时刻的连续可见区间
     */
    public static List<TimeInterval> scan(Instant start,
                                          Instant end,
                                          double coarseStepSec,
                                          double fineStepSec,
                                          Predicate<Instant> visibleAt) {
        // 兼容旧调用方：未显式声明最小关注事件时保持原来的端点扫描策略。
        return scan(start, end, coarseStepSec, fineStepSec, coarseStepSec, visibleAt);
    }

    /**
     * 使用端点粗选、递归二分探测和精选边界定位可见窗口。两端同为不可见时，也会以
     * {@code minEventSec} 为检测下界探测可能夹在中间的短窗口；更短事件允许按约定忽略。
     *
     * @param minEventSec 最小关注事件时长（秒），决定二分深度
     */
    public static List<TimeInterval> scan(Instant start,
                                          Instant end,
                                          double coarseStepSec,
                                          double fineStepSec,
                                          double minEventSec,
                                          Predicate<Instant> visibleAt) {
        return scan(start, end, coarseStepSec, fineStepSec, minEventSec, visibleAt, null);
    }

    /**
     * 扫描可见窗口并可选地回传采样统计。上层应把多个组合的统计汇总为一次规划日志，
     * 不应在此基础组件内逐组合输出日志。
     *
     * @param statisticsConsumer 可选的单次扫描统计接收方
     */
    public static List<TimeInterval> scan(Instant start,
                                          Instant end,
                                          double coarseStepSec,
                                          double fineStepSec,
                                          double minEventSec,
                                          Predicate<Instant> visibleAt,
                                          Consumer<ScanStatistics> statisticsConsumer) {
        if (visibleAt == null) {
            throw new IllegalArgumentException("扫描可见性函数无效");
        }
        List<StateInterval<Boolean>> stateIntervals = scanStates(start, end, coarseStepSec, fineStepSec,
                minEventSec, instant -> visibleAt.test(instant) ? Boolean.TRUE : null, statisticsConsumer);
        List<TimeInterval> windows = new ArrayList<TimeInterval>();
        double totalSec = secondsBetween(start, end);
        for (StateInterval<Boolean> stateInterval : stateIntervals) {
            if (Boolean.TRUE.equals(stateInterval.getState())) {
                TimeInterval stateIntervalWindow = stateInterval.getInterval();
                // 可见窗口沿用既有语义：结束边界记录最后一个可见精选采样点，而不是首个不可见点。
                double endSec = stateIntervalWindow.getEndSec() < totalSec
                        ? stateIntervalWindow.getEndSec() - fineStepSec : stateIntervalWindow.getEndSec();
                if (endSec > stateIntervalWindow.getStartSec()) {
                    windows.add(TimeInterval.of(stateIntervalWindow.getStartSec(), endSec));
                }
            }
        }
        return windows;
    }

    /**
     * 使用粗选步长定位状态可能变化的区间，再以精选步长输出每个稳定且非空的状态区间。
     * null 状态表示当前不可见，因此不会出现在返回集合中；状态切换时刻取第一个新状态精选采样点。
     *
     * @param start 扫描开始时刻
     * @param end 扫描结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选状态边界步长（秒）
     * @param stateAt 指定时刻的状态；null 表示不可见
     * @param <T> 状态签名类型，必须正确实现 equals
     * @return 每段状态恒定且非空的相对时间区间
     */
    public static <T> List<StateInterval<T>> scanStates(Instant start,
                                                          Instant end,
                                                          double coarseStepSec,
                                                          double fineStepSec,
                                                          Function<Instant, T> stateAt) {
        // 兼容旧调用方：没有显式最小事件时保留原有的端点差异才精化的行为。
        return scanStates(start, end, coarseStepSec, fineStepSec, coarseStepSec, stateAt);
    }

    /**
     * 扫描稳定状态区间；当粗选端点相同而内部采样发现不同状态时，对整个粗选区间执行精选，
     * 因而可识别 A→B→A 这类端点相同的状态回跳。
     *
     * @param minEventSec 最小关注事件时长（秒），同时是自适应二分的检测下界
     */
    public static <T> List<StateInterval<T>> scanStates(Instant start,
                                                          Instant end,
                                                          double coarseStepSec,
                                                          double fineStepSec,
                                                          double minEventSec,
                                                          Function<Instant, T> stateAt) {
        return scanStates(start, end, coarseStepSec, fineStepSec, minEventSec, stateAt, null);
    }

    /**
     * 扫描稳定状态区间并向调用方返回本次采样统计。统计通过回调交给规划服务汇总，避免每个
     * “卫星—目标”组合单独输出 INFO 日志而造成日志洪泛。
     *
     * @param statisticsConsumer 可选统计接收方；为 {@code null} 时不收集外部统计
     */
    public static <T> List<StateInterval<T>> scanStates(Instant start,
                                                          Instant end,
                                                          double coarseStepSec,
                                                          double fineStepSec,
                                                          double minEventSec,
                                                          Function<Instant, T> stateAt,
                                                          Consumer<ScanStatistics> statisticsConsumer) {
        validate(start, end, coarseStepSec, fineStepSec, minEventSec, stateAt);
        long totalNanos = nanosBetween(start, end);
        long coarseStepNanos = nanosFromSeconds(coarseStepSec);
        long fineStepNanos = nanosFromSeconds(fineStepSec);
        int adaptiveDepth = adaptiveDepth(coarseStepSec, minEventSec);
        SamplingCounter samplingCounter = new SamplingCounter();
        List<StateInterval<T>> intervals = new ArrayList<StateInterval<T>>();
        long previousOffsetNanos = 0L;
        SampledStateRuns<T> runs = new SampledStateRuns<T>(samplingCounter.evaluate(stateAt, start), 0L);
        while (previousOffsetNanos < totalNanos) {
            long currentOffsetNanos = Math.min(totalNanos, previousOffsetNanos + coarseStepNanos);
            T nextState = samplingCounter.evaluate(stateAt, atOffset(start, currentOffsetNanos));
            if (!Objects.equals(runs.getState(), nextState)) {
                scanChangedRange(start, previousOffsetNanos, currentOffsetNanos,
                        fineStepNanos, runs, stateAt, intervals, samplingCounter);
            } else if (adaptiveDepth > 0 && containsAdaptiveStateChange(start, previousOffsetNanos,
                    currentOffsetNanos, adaptiveDepth, runs.getState(), stateAt, samplingCounter)) {
                // 内部探针发现变化后，必须细扫完整粗选区间，才能同时收集进入和离开两条边界。
                scanChangedRange(start, previousOffsetNanos, currentOffsetNanos,
                        fineStepNanos, runs, stateAt, intervals, samplingCounter);
            }
            previousOffsetNanos = currentOffsetNanos;
        }
        addInterval(intervals, runs.getStart(), totalNanos, runs.getState());
        if (statisticsConsumer != null) {
            statisticsConsumer.accept(samplingCounter.toStatistics());
        }
        return intervals;
    }

    /** 在粗选端点状态不同的候选区间内，用精选网格收集所有状态切换。 */
    private static <T> void scanChangedRange(Instant start,
                                                            long lowerNanos,
                                                            long upperNanos,
                                                            long fineStepNanos,
                                                            SampledStateRuns<T> runs,
                                                            Function<Instant, T> stateAt,
                                                            List<StateInterval<T>> intervals,
                                                            SamplingCounter samplingCounter) {
        samplingCounter.recordRefinedRange();
        long offsetNanos = lowerNanos;
        while (offsetNanos < upperNanos) {
            offsetNanos = Math.min(upperNanos, offsetNanos + fineStepNanos);
            T scannedState = samplingCounter.evaluate(stateAt, atOffset(start, offsetNanos));
            SampledStateRuns.Segment<T> completed = runs.accept(scannedState, offsetNanos);
            if (completed != null) {
                // 空间切分取首个新状态点，不能套弹道的最后可见原点边界。
                addInterval(intervals, completed.getStart(), offsetNanos, completed.getState());
            }
        }
    }

    /** 仅在状态非空且区间具有正长度时保存稳定区间。 */
    private static <T> void addInterval(List<StateInterval<T>> intervals,
                                        long startNanos,
                                        long endNanos,
                                        T state) {
        if (state == null || endNanos <= startNanos) {
            return;
        }
        double startSec = secondsFromNanos(startNanos);
        double endSec = secondsFromNanos(endNanos);
        if (!intervals.isEmpty()) {
            StateInterval<T> previous = intervals.get(intervals.size() - 1);
            if (Objects.equals(previous.getState(), state)
                    && previous.getInterval().getEndSec() == startSec) {
                intervals.set(intervals.size() - 1, new StateInterval<T>(
                        TimeInterval.of(previous.getInterval().getStartSec(), endSec), state));
                return;
            }
        }
        intervals.add(new StateInterval<T>(TimeInterval.of(startSec, endSec), state));
    }

    /** 校验时间边界、步长和状态函数，避免无效配置进入高成本扫描。 */
    private static <T> void validate(Instant start,
                                     Instant end,
                                     double coarseStepSec,
                                     double fineStepSec,
                                     double minEventSec,
                                     Function<Instant, T> stateAt) {
        if (start == null || end == null || !end.isAfter(start) || !Double.isFinite(coarseStepSec)
                || !Double.isFinite(fineStepSec) || coarseStepSec <= 0.0 || fineStepSec <= 0.0
                || fineStepSec > coarseStepSec || !Double.isFinite(minEventSec) || minEventSec < fineStepSec
                || stateAt == null) {
            throw new IllegalArgumentException("扫描时间段或步长无效");
        }
    }

    /** 根据粗选步长和最小关注事件推导二分深度：60s/30s 为 1 层，60s/10s 为 3 层。 */
    private static int adaptiveDepth(double coarseStepSec, double minEventSec) {
        int depth = 0;
        double leafWidthSec = coarseStepSec;
        while (leafWidthSec > minEventSec) {
            depth++;
            if (depth > MAX_ADAPTIVE_DEPTH) {
                throw new IllegalArgumentException("最小关注事件时长过小，扫描采样预算超限");
            }
            leafWidthSec /= 2.0;
        }
        return depth;
    }

    /** 递归采样粗选区间的中点；任一探针与端点状态不同即表明该区间需要完整精选。 */
    private static <T> boolean containsAdaptiveStateChange(Instant start,
                                                            long lowerNanos,
                                                            long upperNanos,
                                                            int depth,
                                                            T endpointState,
                                                            Function<Instant, T> stateAt,
                                                            SamplingCounter samplingCounter) {
        List<Long> probeOffsets = new ArrayList<Long>();
        collectAdaptiveProbeOffsets(lowerNanos, upperNanos, depth, probeOffsets);
        for (Long probeOffset : probeOffsets) {
            if (!Objects.equals(endpointState, samplingCounter.evaluateAdaptiveProbe(stateAt,
                    atOffset(start, probeOffset.longValue())))) {
                return true;
            }
        }
        return false;
    }

    /** 对当前区间先采中点，再递归左右子区间，保证实现语义为有界递归二分而非单次中点探测。 */
    private static void collectAdaptiveProbeOffsets(long lowerNanos,
                                                    long upperNanos,
                                                    int depth,
                                                    List<Long> probeOffsets) {
        if (depth <= 0 || upperNanos - lowerNanos <= 1L) {
            return;
        }
        long middleNanos = lowerNanos + (upperNanos - lowerNanos) / 2L;
        probeOffsets.add(Long.valueOf(middleNanos));
        collectAdaptiveProbeOffsets(lowerNanos, middleNanos, depth - 1, probeOffsets);
        collectAdaptiveProbeOffsets(middleNanos, upperNanos, depth - 1, probeOffsets);
    }

    /** 将以秒为单位的步长转为整数纳秒，避免毫秒精选循环出现浮点累积误差。 */
    private static long nanosFromSeconds(double seconds) {
        return Math.round(seconds * 1_000_000_000.0);
    }

    /** 计算两个时刻之间的纳秒差，供状态扫描使用整数时间刻度。 */
    private static long nanosBetween(Instant start, Instant end) {
        return Math.addExact(Math.multiplyExact(end.getEpochSecond() - start.getEpochSecond(), 1_000_000_000L),
                end.getNano() - start.getNano());
    }

    /** 将整数纳秒偏移转换为相对秒，供既有 TimeInterval 模型和上层 Instant 转换使用。 */
    private static double secondsFromNanos(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    /**
     * 单次扫描的采样量。该不可变值对象不含轨道、目标或时间等敏感业务明细，适合上层汇总写日志。
     */
    public static final class ScanStatistics {
        private final long stateEvaluations;
        private final long adaptiveProbeEvaluations;
        private final long refinedRangeCount;

        private ScanStatistics(long stateEvaluations, long adaptiveProbeEvaluations, long refinedRangeCount) {
            this.stateEvaluations = stateEvaluations;
            this.adaptiveProbeEvaluations = adaptiveProbeEvaluations;
            this.refinedRangeCount = refinedRangeCount;
        }

        /** @return 调用状态函数的总次数，含粗选、自适应探针和精选。 */
        public long getStateEvaluations() {
            return stateEvaluations;
        }

        /** @return 自适应递归二分阶段实际取用的探针次数。 */
        public long getAdaptiveProbeEvaluations() {
            return adaptiveProbeEvaluations;
        }

        /** @return 进入完整精选的粗选候选区间数。 */
        public long getRefinedRangeCount() {
            return refinedRangeCount;
        }
    }

    /** 仅在一次扫描调用内存活的可变计数器，避免将共享计数器暴露给并发扫描线程。 */
    private static final class SamplingCounter {
        private long stateEvaluations;
        private long adaptiveProbeEvaluations;
        private long refinedRangeCount;

        private <T> T evaluate(Function<Instant, T> stateAt, Instant instant) {
            stateEvaluations++;
            return stateAt.apply(instant);
        }

        private <T> T evaluateAdaptiveProbe(Function<Instant, T> stateAt, Instant instant) {
            adaptiveProbeEvaluations++;
            return evaluate(stateAt, instant);
        }

        private void recordRefinedRange() {
            refinedRangeCount++;
        }

        private ScanStatistics toStatistics() {
            return new ScanStatistics(stateEvaluations, adaptiveProbeEvaluations, refinedRangeCount);
        }
    }

    /**
     * 在粗选发现“不可见到可见”翻转的区间内，按精选步长定位第一个可见采样点。
     *
     * @param start 扫描开始时刻
     * @param lower 粗选翻转区间下界（相对秒）
     * @param upper 粗选翻转区间上界（相对秒）
     * @param fineStepSec 精选步长（秒）
     * @param visibleAt 可见性判定函数
     * @return 可见窗口开始偏移（秒）
     */
    private static double firstVisibleOffset(Instant start,
                                             double lower,
                                             double upper,
                                             double fineStepSec,
                                             Predicate<Instant> visibleAt) {
        for (double offset = lower; offset <= upper; offset = Math.min(upper, offset + fineStepSec)) {
            if (visibleAt.test(atOffset(start, offset))) {
                return offset;
            }
            if (offset == upper) {
                break;
            }
        }
        return upper;
    }

    /**
     * 在粗选发现“可见到不可见”翻转的区间内，按精选步长定位最后一个可见采样点。
     *
     * @param start 扫描开始时刻
     * @param lower 粗选翻转区间下界（相对秒）
     * @param upper 粗选翻转区间上界（相对秒）
     * @param fineStepSec 精选步长（秒）
     * @param visibleAt 可见性判定函数
     * @return 可见窗口结束偏移（秒）
     */
    private static double lastVisibleOffset(Instant start,
                                            double lower,
                                            double upper,
                                            double fineStepSec,
                                            Predicate<Instant> visibleAt) {
        double lastVisible = lower;
        for (double offset = lower; offset <= upper; offset = Math.min(upper, offset + fineStepSec)) {
            if (visibleAt.test(atOffset(start, offset))) {
                lastVisible = offset;
            }
            if (offset == upper) {
                break;
            }
        }
        return lastVisible;
    }

    /**
     * 将相对秒偏移转换为绝对时刻，保留精化步长带来的纳秒精度。
     *
     * @param start 扫描开始时刻
     * @param seconds 相对开始时刻的秒偏移
     * @return 对应的绝对时刻
     */
    private static Instant atOffset(Instant start, double seconds) {
        long nanos = Math.round(seconds * 1_000_000_000.0);
        return start.plusNanos(nanos);
    }

    /** 将整数纳秒偏移叠加到扫描起点，保证状态扫描的毫秒网格不受浮点误差影响。 */
    private static Instant atOffset(Instant start, long nanos) {
        return start.plusNanos(nanos);
    }

    /**
     * 计算两个时刻之间的带小数秒间隔，避免只使用整秒导致扫描末端丢失。
     *
     * @param start 开始时刻
     * @param end 结束时刻
     * @return 相隔秒数
     */
    private static double secondsBetween(Instant start, Instant end) {
        return end.getEpochSecond() - start.getEpochSecond()
                + (end.getNano() - start.getNano()) / 1_000_000_000.0;
    }
}
