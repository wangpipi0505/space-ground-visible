package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPair;
import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPairResult;
import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPlanner;
import com.runisys.orbit.spacevisibility.domain.space.illumination.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.window.ScanStatisticsAccumulator;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编排有限批次的站星并行下传计算，使用领域DownlinkPlanner及原光照/保护规则。
 * 结果按卫星和站点标识整理；单链路异常单独记录，不把失败链路当作正常空窗口。
 */
public final class DownlinkPlanningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownlinkPlanningService.class);

    private final Executor executor;
    private final int maxPairTasks;
    private final int pairBatchSize;
    private final double scanMinEventSec;
    private final double sunAngularRadiusDeg;

    public DownlinkPlanningService(Executor executor, int maxPairTasks) {
        this(executor, maxPairTasks, maxPairTasks, 30.0, SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /**
     * 创建同步下传规划服务，超过单批容量的站星组合按批次提交并等待结果。
     *
     * @param executor 共享计算执行器
     * @param maxPairTasks 单次请求允许的站星组合总数上限
     * @param pairBatchSize 单批提交并等待完成的站星组合数
     */
    public DownlinkPlanningService(Executor executor, int maxPairTasks, int pairBatchSize) {
        this(executor, maxPairTasks, pairBatchSize, 30.0, SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /**
     * 创建可配置扫描精度与本影口径的下传规划服务；Spring 生产装配使用此构造器，
     * 旧测试和工具构造器保持历史兼容默认值。
     */
    public DownlinkPlanningService(Executor executor,
                                   int maxPairTasks,
                                   int pairBatchSize,
                                   double scanMinEventSec,
                                   double sunAngularRadiusDeg) {
        if (executor == null || maxPairTasks < 1 || pairBatchSize < 1 || !Double.isFinite(scanMinEventSec)
                || scanMinEventSec <= 0.0 || !Double.isFinite(sunAngularRadiusDeg) || sunAngularRadiusDeg < 0.0) {
            throw new IllegalArgumentException("下传规划并发参数无效");
        }
        this.executor = executor;
        this.maxPairTasks = maxPairTasks;
        this.pairBatchSize = pairBatchSize;
        this.scanMinEventSec = scanMinEventSec;
        this.sunAngularRadiusDeg = sunAngularRadiusDeg;
    }

    /**
     * 以固定线程池并行计算所有卫星—地面站组合的稳定下传窗口，并按卫星和站点标识排序输出。
     *
     * @param pairs 已解析轨道和临时约束的卫星—地面站计算任务
     * @return 每个组合对应的稳定下传窗口
     */
    public List<DownlinkPairResult> plan(List<DownlinkPair> pairs) {
        return planWithOutcome(pairs).getResults();
    }

    /**
     * 计算下传窗口并保留单链路异常，避免任一传播失败中断同批次其他站星关系。
     */
    public DownlinkPlanningOutcome planWithOutcome(List<DownlinkPair> pairs) {
        long startedNanos = System.nanoTime();
        if (pairs == null || pairs.isEmpty()) {
            return new DownlinkPlanningOutcome(new ArrayList<DownlinkPairResult>(),
                    new ArrayList<DownlinkSkippedLink>());
        }
        if (pairs.size() > maxPairTasks) {
            throw new IllegalArgumentException("卫星与地面站组合数量超过计算上限");
        }
        List<PendingDownlinkPair> tasks = new ArrayList<PendingDownlinkPair>();
        List<DownlinkPairResult> results = new ArrayList<DownlinkPairResult>();
        List<DownlinkSkippedLink> skippedLinks = new ArrayList<DownlinkSkippedLink>();
        // 只在整个请求完成后记录一条扫描汇总日志，避免每条站星链路各自写 INFO。
        ScanStatisticsAccumulator scanStatistics = new ScanStatisticsAccumulator();
        int batchCount = 0;
        for (DownlinkPair pair : pairs) {
            tasks.add(new PendingDownlinkPair(pair,
                    CompletableFuture.supplyAsync(() -> calculateWithContext(pair, scanStatistics), executor)));
            if (tasks.size() == pairBatchSize) {
                collectBatch(results, skippedLinks, tasks);
                batchCount++;
            }
        }
        if (!tasks.isEmpty()) {
            collectBatch(results, skippedLinks, tasks);
            batchCount++;
        }
        results.sort(Comparator.comparing(DownlinkPairResult::getSatelliteId)
                .thenComparing(DownlinkPairResult::getStationId));
        skippedLinks.sort(Comparator.comparing(DownlinkSkippedLink::getSatelliteId)
                .thenComparing(DownlinkSkippedLink::getStationId));
        LOGGER.info("同步下传规划完成：组合数={}，批次数={}，正常链路数={}，跳过链路数={}，跳过分类={}，"
                        + "扫描次数={}，采样次数={}，递归探针={}，精选区间={}，耗时={}ms",
                pairs.size(), batchCount, results.size(), skippedLinks.size(), summarizeSkippedLinks(skippedLinks),
                scanStatistics.getScanCount(), scanStatistics.getStateEvaluations(),
                scanStatistics.getAdaptiveProbeEvaluations(), scanStatistics.getRefinedRangeCount(),
                (System.nanoTime() - startedNanos) / 1_000_000L);
        return new DownlinkPlanningOutcome(results, skippedLinks);
    }

    /** 收集一批同步等待完成的站星组合结果，并把单链路异常降级为可审计跳过项。 */
    private void collectBatch(List<DownlinkPairResult> results,
                              List<DownlinkSkippedLink> skippedLinks,
                              List<PendingDownlinkPair> tasks) {
        for (PendingDownlinkPair pendingPair : tasks) {
            try {
                results.add(pendingPair.getTask().join());
            } catch (CompletionException exception) {
                Throwable cause = rootCause(exception);
                String reason = formatFailureReason(cause);
                DownlinkPair pair = pendingPair.getPair();
                skippedLinks.add(new DownlinkSkippedLink(pair.getSatelliteId(), pair.getStationId(),
                        DownlinkSkippedLink.Stage.SCAN, reason));
                LOGGER.warn("跳过下传规划链路：卫星={}，地面站={}，阶段=SCAN，原因={}",
                        pair.getSatelliteId(), pair.getStationId(), reason);
            }
        }
        tasks.clear();
    }

    /** 将跳过链路按阶段与异常类别汇总，保持 INFO 日志短小且可用于识别集中故障。 */
    private String summarizeSkippedLinks(List<DownlinkSkippedLink> skippedLinks) {
        Map<String, Integer> summary = new TreeMap<String, Integer>();
        for (DownlinkSkippedLink skippedLink : skippedLinks) {
            String key = skippedLink.getStage().name() + "/" + reasonCategory(skippedLink.getReason());
            Integer count = summary.get(key);
            summary.put(key, count == null ? Integer.valueOf(1) : Integer.valueOf(count.intValue() + 1));
        }
        return summary.toString();
    }

    /** 原因首段为异常类型，用于把包含动态业务明细的失败文本归并为稳定类别。 */
    private String reasonCategory(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "UNKNOWN";
        }
        int separator = reason.indexOf(':');
        return separator > 0 ? reason.substring(0, separator) : reason;
    }

    /** 提取异步包装链末端根因，供日志和接口返回统一使用。 */
    private Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String formatFailureReason(Throwable exception) {
        return PlanningFailureReasonFormatter.toUserMessage(exception);
    }

    /**
     * 调用领域下传规划器，综合站点仰角、卫星光照、前后保护和允许断链条件生成稳定窗口。
     *
     * @param pair 单个卫星—地面站计算任务
     * @return 该组合的下传窗口结果
     */
    private DownlinkPairResult calculate(DownlinkPair pair, ScanStatisticsAccumulator scanStatistics) {
        List<TimeInterval> windows = DownlinkPlanner.planEffectiveWindows(
                pair.getOrbit(), pair.getStation(), pair.getStartTime(), pair.getEndTime(),
                pair.getCoarseStepSec(), pair.getFineStepSec(), pair.getMinElevationDeg(), pair.getMaxElevationDeg(),
                pair.getPreGuardSec(), pair.getPostGuardSec(), pair.getAllowedGapSec(), pair.getIlluminationRequirement(),
                scanMinEventSec, sunAngularRadiusDeg, scanStatistics::record);
        return new DownlinkPairResult(pair.getSatelliteId(), pair.getStationId(), windows);
    }

    /** 为异步计算失败补充站星关系，供全局异常处理器记录可重放的故障上下文。 */
    private DownlinkPairResult calculateWithContext(DownlinkPair pair, ScanStatisticsAccumulator scanStatistics) {
        try {
            return calculate(pair, scanStatistics);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("下传规划组合失败：卫星=" + pair.getSatelliteId()
                    + "，地面站=" + pair.getStationId(), exception);
        }
    }

    /** 将 future 与其站星关系绑定，保证异步异常能准确归属到一条下传链路。 */
    private static final class PendingDownlinkPair {
        private final DownlinkPair pair;
        private final CompletableFuture<DownlinkPairResult> task;

        private PendingDownlinkPair(DownlinkPair pair, CompletableFuture<DownlinkPairResult> task) {
            this.pair = pair;
            this.task = task;
        }

        private DownlinkPair getPair() {
            return pair;
        }

        private CompletableFuture<DownlinkPairResult> getTask() {
            return task;
        }
    }
}
