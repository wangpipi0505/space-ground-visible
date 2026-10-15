package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationConstraint;
import com.runisys.orbit.spacevisibility.domain.space.illumination.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.space.visibility.ObservationSampleEvaluator;
import com.runisys.orbit.spacevisibility.domain.space.visibility.OrbitPositionCache;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityArc;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityObserver;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairResult;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairStatistics;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilitySample;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityTarget;
import com.runisys.orbit.spacevisibility.domain.window.ScanStatisticsAccumulator;
import com.runisys.orbit.spacevisibility.domain.window.StateInterval;
import com.runisys.orbit.spacevisibility.domain.window.TimeWindowScanner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以有限批次并行计算观测星—目标组合，再执行原共同观测窗口与最小时长约束。
 * 进度及取消在既有批次边界协作，输入顺序/输出整理规则保持；失败组合有独立跳过原因。
 */
public final class VisibilityPlanningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisibilityPlanningService.class);

    private final Executor executor;
    private final int pairBatchSize;
    private final int observerOrbitCacheSize;
    private final ObservationSampleEvaluator observationSampleEvaluator;
    private final double scanMinEventSec;
    private final double sunAngularRadiusDeg;

    public VisibilityPlanningService(Executor executor, int pairBatchSize) {
        this(executor, pairBatchSize, 10_000, 10.0, SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /**
     * 创建同步观测规划服务，组合任务按小批次提交至受限线程池。
     *
     * @param executor 共享计算执行器
     * @param pairBatchSize 单批提交并等待完成的观测星—目标组合数
     * @param observerOrbitCacheSize 单颗观测星在本次规划内可缓存的时刻位置数
     */
    public VisibilityPlanningService(Executor executor, int pairBatchSize, int observerOrbitCacheSize) {
        this(executor, pairBatchSize, observerOrbitCacheSize, 10.0, SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /** 生产装配显式传入空间目标扫描下界和严格几何本影配置。 */
    public VisibilityPlanningService(Executor executor,
                                     int pairBatchSize,
                                     int observerOrbitCacheSize,
                                     double scanMinEventSec,
                                     double sunAngularRadiusDeg) {
        if (executor == null || pairBatchSize < 1 || observerOrbitCacheSize < 1 || !Double.isFinite(scanMinEventSec)
                || scanMinEventSec <= 0.0 || !Double.isFinite(sunAngularRadiusDeg) || sunAngularRadiusDeg < 0.0) {
            throw new IllegalArgumentException("观测规划并发参数无效");
        }
        this.executor = executor;
        this.pairBatchSize = pairBatchSize;
        this.observerOrbitCacheSize = observerOrbitCacheSize;
        this.observationSampleEvaluator = new ObservationSampleEvaluator();
        this.scanMinEventSec = scanMinEventSec;
        this.sunAngularRadiusDeg = sunAngularRadiusDeg;
    }

    /**
     * 以固定线程池并行计算每一个“观测星—目标”组合，并按标识稳定排序，保证同一输入得到可预测的输出顺序。
     * 按 maxPairTasks 大小分批提交固定线程池，避免一次全库遍历创建过多异步任务。
     *
     * @param observers 我方观测卫星及其轨道、载荷约束
     * @param targets 待计算的空间目标及其轨道
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒）
     * @return 每对卫星和目标的可见弧段
     */
    public List<VisibilityPairResult> plan(List<VisibilityObserver> observers,
                                           List<VisibilityTarget> targets,
                                           Instant start,
                                           Instant end,
                                           double coarseStepSec,
                                           double fineStepSec) {
        return plan(observers, targets, start, end, coarseStepSec, fineStepSec,
                IlluminationConstraint.from("ANY", java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList()));
    }

    /**
     * 在固定线程池中按卫星—目标对并行扫描，且在每个采样时刻应用本次规划临时的光照组合约束。
     *
     * @param observers 我方观测卫星及其轨道、载荷约束
     * @param targets 待计算的空间目标及其轨道
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选状态边界步长（秒）
     * @param illuminationConstraint 观测星—目标光照组合临时约束
     * @return 每对卫星和目标的状态稳定可见弧段
     */
    public List<VisibilityPairResult> plan(List<VisibilityObserver> observers,
                                           List<VisibilityTarget> targets,
                                           Instant start,
                                           Instant end,
                                           double coarseStepSec,
                                           double fineStepSec,
                                           IlluminationConstraint illuminationConstraint) {
        return plan(observers, targets, start, end, coarseStepSec, fineStepSec, illuminationConstraint, null, null);
    }

    /**
     * 在固定线程池中按卫星—目标对并行扫描，支持按批次上报进度和在批次边界响应取消。
     *
     * @param observers 我方观测卫星及其轨道、载荷约束
     * @param targets 待计算的空间目标及其轨道
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选状态边界步长（秒）
     * @param illuminationConstraint 观测星—目标光照组合临时约束
     * @param progressCallback 批次收集完成后的进度回调（已完成组合数、总组合数），可空
     * @param cancellationCheck 批次边界的取消检查，返回 true 时抛出 CancellationException，可空
     * @return 每对卫星和目标的状态稳定可见弧段
     */
    public List<VisibilityPairResult> plan(List<VisibilityObserver> observers,
                                           List<VisibilityTarget> targets,
                                           Instant start,
                                           Instant end,
                                           double coarseStepSec,
                                           double fineStepSec,
                                           IlluminationConstraint illuminationConstraint,
                                           BiConsumer<Long, Long> progressCallback,
                                           Supplier<Boolean> cancellationCheck) {
        return planWithOutcome(observers, targets, start, end, coarseStepSec, fineStepSec,
                illuminationConstraint, progressCallback, cancellationCheck).getResults();
    }

    /**
     * 在固定线程池中按卫星—目标对并行扫描，并返回正常结果及单组合跳过信息。
     *
     * <p>保留原 {@link #plan(List, List, Instant, Instant, double, double, IlluminationConstraint,
     * BiConsumer, Supplier)} 返回列表的兼容入口，业务门面应使用本方法透出跳过清单。</p>
     */
    public VisibilityPlanningOutcome planWithOutcome(List<VisibilityObserver> observers,
                                                      List<VisibilityTarget> targets,
                                                      Instant start,
                                                      Instant end,
                                                      double coarseStepSec,
                                                      double fineStepSec,
                                                      IlluminationConstraint illuminationConstraint,
                                                      BiConsumer<Long, Long> progressCallback,
                                                      Supplier<Boolean> cancellationCheck) {
        long startedNanos = System.nanoTime();
        long pairCount = (long) observers.size() * targets.size();
        List<VisibilityPairResult> results = new ArrayList<VisibilityPairResult>();
        List<VisibilitySkippedPair> skippedPairs = new ArrayList<VisibilitySkippedPair>();
        // 每个规划请求独立持有统计器；future 可并发写入，收集完成后只在本方法输出一次汇总日志。
        ScanStatisticsAccumulator scanStatistics = new ScanStatisticsAccumulator();
        List<PendingVisibilityPair> batchTasks = new ArrayList<PendingVisibilityPair>();
        Map<VisibilityObserver, OrbitPositionCache> observerPositionCaches = new IdentityHashMap<VisibilityObserver, OrbitPositionCache>();
        for (VisibilityObserver observer : observers) {
            observerPositionCaches.put(observer, new OrbitPositionCache(observerOrbitCacheSize));
        }
        int batchCount = 0;
        long completedPairs = 0L;
        checkCancelled(cancellationCheck);
        for (VisibilityObserver observer : observers) {
            for (VisibilityTarget target : targets) {
                OrbitPositionCache observerPositionCache = observerPositionCaches.get(observer);
                CompletableFuture<VisibilityPairResult> task = CompletableFuture.supplyAsync(
                        () -> calculatePairWithContext(observer, target, start, end, coarseStepSec, fineStepSec,
                                illuminationConstraint, observerPositionCache, scanStatistics), executor);
                batchTasks.add(new PendingVisibilityPair(observer, target, task));
                if (batchTasks.size() == pairBatchSize) {
                    checkCancelled(cancellationCheck);
                    collectBatch(results, skippedPairs, batchTasks);
                    batchCount++;
                    completedPairs += pairBatchSize;
                    reportProgress(progressCallback, completedPairs, pairCount);
                }
            }
        }
        if (!batchTasks.isEmpty()) {
            checkCancelled(cancellationCheck);
            int lastBatchSize = batchTasks.size();
            collectBatch(results, skippedPairs, batchTasks);
            batchCount++;
            completedPairs += lastBatchSize;
            reportProgress(progressCallback, completedPairs, pairCount);
        }
        results.sort(Comparator.comparing(VisibilityPairResult::getObserverSatelliteId)
                .thenComparing(VisibilityPairResult::getTargetId));
        skippedPairs.sort(Comparator.comparing(VisibilitySkippedPair::getObserverSatelliteId)
                .thenComparing(VisibilitySkippedPair::getTargetId)
                .thenComparing(VisibilitySkippedPair::getStage));
        LOGGER.info("同步目标观测规划完成：组合数={}，批次数={}，有效组合数={}，跳过组合数={}，跳过分类={}，"
                        + "扫描次数={}，采样次数={}，递归探针={}，精选区间={}，耗时={}ms",
                pairCount, batchCount, results.size(), skippedPairs.size(), summarizeSkippedPairs(skippedPairs),
                scanStatistics.getScanCount(), scanStatistics.getStateEvaluations(),
                scanStatistics.getAdaptiveProbeEvaluations(), scanStatistics.getRefinedRangeCount(),
                (System.nanoTime() - startedNanos) / 1_000_000L);
        return new VisibilityPlanningOutcome(results, skippedPairs);
    }

    /** 收集一批异步计算结果；单组合异常转为跳过项，不能升级为整批失败。 */
    private void collectBatch(List<VisibilityPairResult> results,
                              List<VisibilitySkippedPair> skippedPairs,
                              List<PendingVisibilityPair> batchTasks) {
        for (PendingVisibilityPair pendingPair : batchTasks) {
            try {
                VisibilityPairResult result = pendingPair.getTask().join();
                if (!result.getArcs().isEmpty()) {
                    results.add(result);
                }
            } catch (CancellationException exception) {
                throw exception;
            } catch (CompletionException exception) {
                Throwable cause = rootCause(exception);
                String reason = formatFailureReason(cause);
                skippedPairs.add(new VisibilitySkippedPair(pendingPair.getObserver().getSatelliteId(),
                        pendingPair.getTarget().getTargetId(), VisibilitySkippedPair.Stage.SCAN, reason));
                LOGGER.warn("跳过可见性规划组合：观测星={}，目标={}，阶段=SCAN，原因={}",
                        pendingPair.getObserver().getSatelliteId(), pendingPair.getTarget().getTargetId(), reason);
            }
        }
        batchTasks.clear();
    }

    /** 将大量明细失败压缩为稳定分类，供单条请求级 INFO 日志快速判断故障是否集中。 */
    private String summarizeSkippedPairs(List<VisibilitySkippedPair> skippedPairs) {
        Map<String, Integer> summary = new TreeMap<String, Integer>();
        for (VisibilitySkippedPair skippedPair : skippedPairs) {
            String key = skippedPair.getStage().name() + "/" + reasonCategory(skippedPair.getReason());
            Integer count = summary.get(key);
            summary.put(key, count == null ? Integer.valueOf(1) : Integer.valueOf(count.intValue() + 1));
        }
        return summary.toString();
    }

    /** 原因摘要首段为异常类别；舍弃易变消息部分以便聚合并避免在 INFO 重复输出详情。 */
    private String reasonCategory(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "UNKNOWN";
        }
        int separator = reason.indexOf(':');
        return separator > 0 ? reason.substring(0, separator) : reason;
    }

    /** 取异步包装链末端的根因，避免向调用方暴露无业务含义的 CompletionException。 */
    private Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 以“异常类型: 消息”提供可展示、可审计的组合失败摘要。 */
    private String formatFailureReason(Throwable exception) {
        return PlanningFailureReasonFormatter.toUserMessage(exception);
    }

    /**
     * 为线程池中的失败补上观测星与目标标识。{@link CompletableFuture#join()} 会包装原始异常，
     * 没有这层上下文时，运维日志只能看到通用 CompletionException，无法重放失败组合。
     */
    private VisibilityPairResult calculatePairWithContext(VisibilityObserver observer,
                                                          VisibilityTarget target,
                                                          Instant start,
                                                          Instant end,
                                                          double coarseStepSec,
                                                          double fineStepSec,
                                                          IlluminationConstraint illuminationConstraint,
                                                          OrbitPositionCache observerPositionCache,
                                                          ScanStatisticsAccumulator scanStatistics) {
        try {
            return calculatePair(observer, target, start, end, coarseStepSec, fineStepSec,
                    illuminationConstraint, observerPositionCache, scanStatistics);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("可见性规划组合失败：观测星=" + observer.getSatelliteId()
                    + "，目标=" + target.getTargetId(), exception);
        }
    }

    /** 在批次边界响应取消请求，取消时立即中断本次规划。 */
    private void checkCancelled(Supplier<Boolean> cancellationCheck) {
        if (cancellationCheck != null && cancellationCheck.get()) {
            throw new CancellationException("可见性规划任务已取消");
        }
    }

    /** 在批次收集完成后上报进度，未注册回调时直接跳过。 */
    private void reportProgress(BiConsumer<Long, Long> progressCallback, long completedPairs, long totalPairs) {
        if (progressCallback != null) {
            progressCallback.accept(completedPairs, totalPairs);
        }
    }

    /**
     * 扫描单个观测星与目标的时间段，逐时刻检查地球遮挡、距离、太阳规避、阳照和载荷角速度条件。
     *
     * @param observer 我方观测卫星
     * @param target 空间目标
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒）
     * @return 单对对象的可见弧段结果
     */
    private VisibilityPairResult calculatePair(VisibilityObserver observer,
                                                VisibilityTarget target,
                                                Instant start,
                                                Instant end,
                                                double coarseStepSec,
                                                double fineStepSec,
                                                IlluminationConstraint illuminationConstraint,
                                                OrbitPositionCache observerPositionCache,
                                                ScanStatisticsAccumulator scanStatistics) {
        OrbitPositionCache targetPositionCache = new OrbitPositionCache(observerOrbitCacheSize);
        // 采样统计只在“几何可见且光照允许”的采样点上累计，口径与可见窗口完全一致。
        VisibilityPairStatistics statistics = new VisibilityPairStatistics();
        List<StateInterval<IlluminationSignature>> intervals = TimeWindowScanner.scanStates(
                start, end, coarseStepSec, fineStepSec, scanMinEventSec, instant -> {
            VisibilitySample sample = observationSampleEvaluator.evaluate(observer, target, observerPositionCache,
                    targetPositionCache, instant, fineStepSec, sunAngularRadiusDeg);
            if (!sample.isGeometryVisible() || !illuminationConstraint.allows(sample.getObserverIllumination(),
                    sample.getTargetIllumination())) {
                return null;
            }
            statistics.accept(sample);
            return new IlluminationSignature(sample.getObserverIllumination(), sample.getTargetIllumination());
        }, scanStatistics::record);
        List<VisibilityArc> arcs = new ArrayList<VisibilityArc>();
        for (StateInterval<IlluminationSignature> interval : intervals) {
            IlluminationSignature signature = interval.getState();
            arcs.add(new VisibilityArc(
                    start.plusNanos(Math.round(interval.getInterval().getStartSec() * 1_000_000_000.0)),
                    start.plusNanos(Math.round(interval.getInterval().getEndSec() * 1_000_000_000.0)),
                    java.util.Collections.singletonList(new VisibilityArc.ObserverIllumination(observer.getSatelliteId(),
                            observer.getSatelliteId(), signature.getObserverState())),
                    new VisibilityArc.TargetIllumination(target.getTargetId(), target.getTargetId(),
                            signature.getTargetState())));
        }
        /*
         * WinTool 文档明确说明 startupAngleRate 是可保存的载荷元数据、不会参与观测窗口求解。
         * 因而这里必须原样保留几何、跟踪角速度、太阳规避和光照约束共同确定的稳定弧段；
         * 不能在每个光照状态子弧的起点重新执行“启动捕获”扫描。后者会使 A 星对
         * ONEWEB-0153 等目标在光照状态切换后被提前截断，与文档结果不一致。
         */
        return new VisibilityPairResult(observer.getSatelliteId(), target.getTargetId(), arcs, statistics);
    }

    /** 将 future 与其业务组合绑定，供批次收集时准确生成组合级跳过信息。 */
    private static final class PendingVisibilityPair {
        private final VisibilityObserver observer;
        private final VisibilityTarget target;
        private final CompletableFuture<VisibilityPairResult> task;

        private PendingVisibilityPair(VisibilityObserver observer,
                                      VisibilityTarget target,
                                      CompletableFuture<VisibilityPairResult> task) {
            this.observer = observer;
            this.target = target;
            this.task = task;
        }

        private VisibilityObserver getObserver() {
            return observer;
        }

        private VisibilityTarget getTarget() {
            return target;
        }

        private CompletableFuture<VisibilityPairResult> getTask() {
            return task;
        }
    }

    /** 表示一段稳定可见区间内观测星与目标的精确光照状态组合。 */
    private static final class IlluminationSignature {
        private final com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState observerState;
        private final com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState targetState;

        private IlluminationSignature(com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState observerState,
                                      com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState targetState) {
            this.observerState = observerState;
            this.targetState = targetState;
        }

        private com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState getObserverState() {
            return observerState;
        }

        private com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState getTargetState() {
            return targetState;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IlluminationSignature)) {
                return false;
            }
            IlluminationSignature signature = (IlluminationSignature) other;
            return observerState == signature.observerState && targetState == signature.targetState;
        }

        @Override
        public int hashCode() {
            return 31 * observerState.hashCode() + targetState.hashCode();
        }
    }
}
