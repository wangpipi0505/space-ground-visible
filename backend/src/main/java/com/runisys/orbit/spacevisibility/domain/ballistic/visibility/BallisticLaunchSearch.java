package com.runisys.orbit.spacevisibility.domain.ballistic.visibility;

import com.runisys.orbit.spacevisibility.domain.ballistic.propagation.BallisticOrbitPropagation;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;
import com.runisys.orbit.spacevisibility.domain.window.SampledStateRuns;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticArcSupport.firstVisibilityConstraint;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticArcSupport.hasVisiblePoints;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticArcSupport.stableVisibleArcs;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticSampledWindows.interpolateBallistic;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticSampledWindows.sortedBallistic;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticSampledWindows.visibilitySampleStepSec;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticVisibilityGeometry.isStandardTargetObservable;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticVisibilityGeometry.isTargetObservableFromSatelliteStable;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticVisibilityGeometry.normalizeSensorConfig;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator.*;

/**
 * 弹道发射偏移搜索实现：准备目标采样、并行计算、按输入顺序回填并选择最优结果。
 * 每目标携带独立参考历元，单次搜索独占卫星位置缓存与载荷历史。
 * 保持积分模型、采样节点、稳定弧段、同分取首项及逐星逐载荷累计规则。
 */
final class BallisticLaunchSearch {
    private BallisticLaunchSearch() {
    }

    /** 稳定可见弧段至少需要两个采样点，避免单点抖动形成伪窗口。 */
    private static final int MIN_STABLE_VISIBLE_POINT_COUNT = 2;

    /** 稳定可见弧段最小时长，单位秒；保持很小的默认值，避免改变正常短窗口。 */
    private static final double MIN_STABLE_VISIBLE_DURATION_SEC = 1e-3;

    private static final class PreparedBallisticTarget {
        final BallisticTargetInput input;
        final List<BallisticPoint> points;
        final double sourceReferenceEpochSec;
        final double baseLaunchJ2000Sec;
        final String baseLaunchUtc;

        PreparedBallisticTarget(BallisticTargetInput input,
                                List<BallisticPoint> points,
                                double sourceReferenceEpochSec,
                                double baseLaunchJ2000Sec,
                                String baseLaunchUtc) {
            this.input = input;
            this.points = points;
            this.sourceReferenceEpochSec = sourceReferenceEpochSec;
            this.baseLaunchJ2000Sec = baseLaunchJ2000Sec;
            this.baseLaunchUtc = baseLaunchUtc;
        }
    }

    private static final class IndexedLaunchOffsetCalc {
        final int targetIndex;
        final int offsetIndex;
        final LaunchOffsetCalcResult calc;

        IndexedLaunchOffsetCalc(int targetIndex, int offsetIndex, LaunchOffsetCalcResult calc) {
            this.targetIndex = targetIndex;
            this.offsetIndex = offsetIndex;
            this.calc = calc;
        }
    }

    /**
     * 保存可见点的绝对时间和可选轨迹明细；相对首末时间与点数紧随本方法由共用段记录回填。
     * 用回填前的visiblePointCount识别首点，保持原绝对秒运算与metadata-only输出语义。
     */
    private static void recordVisiblePoint(SatelliteVisibilityResult arc, double longitudeDeg, double latitudeDeg,
            double heightM, double absoluteTimeSec, double relativeTimeSec, boolean includeTrajectoryPoints) {
        if (arc.visiblePointCount == 0) {
            arc.startJ2000Sec = absoluteTimeSec;
        }
        arc.endJ2000Sec = absoluteTimeSec;
        if (includeTrajectoryPoints) {
            arc.trajectoryPoints.add(new VisibleTrajectoryPoint(
                    longitudeDeg, latitudeDeg, heightM, absoluteTimeSec, relativeTimeSec));
        }
    }

    private static boolean includeTrajectoryPoints(int trajectoryPointOutput) {
        if (trajectoryPointOutput == 0) {
            return false;
        }
        if (trajectoryPointOutput == 1) {
            return true;
        }
        throw new IllegalArgumentException("trajectoryPointOutput只能为0或1。");
    }

    private interface SatellitePositionProvider {
        Vector3 position(int satelliteIndex, OrbitElements elements, double absoluteJ2000Sec, OrbitModel orbitModel);
    }

    private static final class CachedSatellitePositionProvider implements SatellitePositionProvider {
        private final List<ConcurrentHashMap<Long, Vector3>> caches;

        CachedSatellitePositionProvider(int satelliteCount) {
            caches = new ArrayList<ConcurrentHashMap<Long, Vector3>>();
            for (int i = 0; i < satelliteCount; i++) {
                caches.add(new ConcurrentHashMap<Long, Vector3>());
            }
        }

        @Override
        public Vector3 position(int satelliteIndex, OrbitElements elements, double absoluteJ2000Sec, OrbitModel orbitModel) {
            ConcurrentHashMap<Long, Vector3> cache = caches.get(satelliteIndex);
            Long key = Long.valueOf(Double.doubleToLongBits(absoluteJ2000Sec));
            Vector3 cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Vector3 computed = propagateAtAbsolute(elements, absoluteJ2000Sec, orbitModel);
            Vector3 previous = cache.putIfAbsent(key, computed);
            return previous == null ? computed : previous;
        }
    }

    static LaunchSearchResultV2 searchSingle(double baseLaunchJ2000Sec, long startOffsetSec, long stepSizeSec,
            long numCalculations, List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints,
            double sensorFovDeg, OrbitModel orbitModel, double targetSampleStepSec, double sourceReferenceEpochSec,
            int trajectoryPointOutput, SensorVisibilityConfig sensorConfig, ProgressCallback progressCallback) {
        validateSearchInputsV2(stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg);
        return searchLaunchOffsetsV2Internal(
                baseLaunchJ2000Sec,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                orbitModel,
                targetSampleStepSec,
                sourceReferenceEpochSec,
                normalizeSensorConfig(sensorConfig, sensorFovDeg),
                includeTrajectoryPoints(trajectoryPointOutput),
                new CachedSatellitePositionProvider(satellites.size()),
                progressCallback);
    }

    private static LaunchSearchResultV2 searchLaunchOffsetsV2Internal(double baseLaunchJ2000Sec,
            long startOffsetSec, long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints, double sensorFovDeg, OrbitModel orbitModel,
            double targetSampleStepSec, double sourceReferenceEpochSec, SensorVisibilityConfig sensorConfig,
            boolean includeTrajectoryPoints, SatellitePositionProvider satellitePositionProvider,
            ProgressCallback progressCallback) {
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;

        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        List<BallisticPoint> samples = interpolateBallistic(sorted, visibilitySampleStepSec(sorted, targetSampleStepSec));
        int offsetCount = checkedCalculationCount(numCalculations);
        LaunchOffsetCalcResult[] indexedResults = new LaunchOffsetCalcResult[offsetCount];
        LaunchOffsetCalcResult best = null;
        String baseLaunchUtc = j2000ToUtcMillis(baseLaunchJ2000Sec);

        runOffsetSearch(
                -1,
                null,
                baseLaunchJ2000Sec,
                baseLaunchUtc,
                startOffsetSec,
                stepSizeSec,
                offsetCount,
                satellites,
                samples,
                sensorFovDeg,
                activeModel,
                sourceReferenceEpochSec,
                sensorConfig,
                includeTrajectoryPoints,
                satellitePositionProvider,
                indexedResults,
                progressCallback);

        List<LaunchOffsetCalcResult> allResults = new ArrayList<LaunchOffsetCalcResult>(offsetCount);
        for (int i = 0; i < offsetCount; i++) {
            LaunchOffsetCalcResult calc = indexedResults[i];
            allResults.add(calc);
            if (best == null || calc.totalObservableDurationSec() > best.totalObservableDurationSec()) {
                best = calc;
            }
        }
        return new LaunchSearchResultV2(
                allResults,
                best,
                j2000ToUtcMillis(sourceReferenceEpochSec),
                hasStandardPayloads(satellites) ? firstVisibilityConstraint(allResults, best)
                        : VisibilityConstraintSummary.fromConfig(sensorConfig));
    }

    static LaunchSearchResultV2 searchMultiple(List<BallisticTargetInput> targets, long startOffsetSec,
            long stepSizeSec, long numCalculations, List<OrbitElements> satellites, double sensorFovDeg,
            OrbitModel orbitModel, double targetSampleStepSec, int trajectoryPointOutput,
            SensorVisibilityConfig sensorConfig, ProgressCallback progressCallback, BallisticCsvLoader loader) throws IOException {
        validateMultiTargetInputs(targets);
        validateSearchInputsV2(stepSizeSec, numCalculations, satellites, java.util.Collections.<BallisticPoint>singletonList(new BallisticPoint()), sensorFovDeg);
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;
        final SensorVisibilityConfig activeSensorConfig = normalizeSensorConfig(sensorConfig, sensorFovDeg);
        final boolean includeTrajectoryPoints = includeTrajectoryPoints(trajectoryPointOutput);
        final int offsetCount = checkedCalculationCount(numCalculations);

        List<PreparedBallisticTarget> preparedTargets = new ArrayList<PreparedBallisticTarget>();
        String firstReferenceUtc = null;
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            BallisticTargetInput input = normalizedTargetInput(targets.get(targetIndex), targetIndex);
            ParsedBallisticCsv parsed = loader.load(input.csvPath);
            List<BallisticPoint> points = parsed.points;
            double sourceReference = parsed.referenceEpochSec;
            String baseLaunchUtc = isBlank(input.baseLaunchUtc)
                    ? j2000ToUtcMillis(sourceReference)
                    : input.baseLaunchUtc.trim();
            double baseLaunchJ2000 = isBlank(input.baseLaunchUtc)
                    ? sourceReference
                    : utcToJ2000Sec(input.baseLaunchUtc.trim());
            if (firstReferenceUtc == null) {
                firstReferenceUtc = parsed.referenceUtc;
            }
            preparedTargets.add(new PreparedBallisticTarget(input, points, sourceReference, baseLaunchJ2000, baseLaunchUtc));
        }

        final List<List<BallisticPoint>> targetSamples = new ArrayList<List<BallisticPoint>>();
        for (PreparedBallisticTarget target : preparedTargets) {
            List<BallisticPoint> sorted = sortedBallistic(target.points);
            targetSamples.add(interpolateBallistic(sorted, visibilitySampleStepSec(sorted, targetSampleStepSec)));
        }

        final long totalProgress = (long) offsetCount * preparedTargets.size();
        final CachedSatellitePositionProvider sharedProvider = new CachedSatellitePositionProvider(satellites.size());
        int threadCount = recommendedSearchThreadCount(totalProgress);
        final LaunchOffsetCalcResult[][] perTargetResults =
                new LaunchOffsetCalcResult[preparedTargets.size()][offsetCount];

        if (threadCount <= 1) {
            long completed = 0L;
            for (int targetIndex = 0; targetIndex < preparedTargets.size(); targetIndex++) {
                PreparedBallisticTarget target = preparedTargets.get(targetIndex);
                List<BallisticPoint> samples = targetSamples.get(targetIndex);
                for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                    perTargetResults[targetIndex][offsetIndex] = analyzeOffset(
                            offsetIndex,
                            target.baseLaunchJ2000Sec,
                            target.input.targetId,
                            target.input.targetName,
                            target.input.csvPath,
                            target.baseLaunchUtc,
                            startOffsetSec,
                            stepSizeSec,
                            satellites,
                            samples,
                            sensorFovDeg,
                            activeModel,
                            target.sourceReferenceEpochSec,
                            activeSensorConfig,
                            includeTrajectoryPoints,
                            sharedProvider);
                    completed++;
                    if (progressCallback != null) {
                        progressCallback.onProgress(completed, totalProgress);
                    }
                }
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                CompletionService<IndexedLaunchOffsetCalc> completion =
                        new ExecutorCompletionService<IndexedLaunchOffsetCalc>(executor);
                int submitted = 0;
                for (int targetIndex = 0; targetIndex < preparedTargets.size(); targetIndex++) {
                    final int ti = targetIndex;
                    final PreparedBallisticTarget target = preparedTargets.get(targetIndex);
                    final List<BallisticPoint> samples = targetSamples.get(targetIndex);
                    for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                        final int oi = offsetIndex;
                        completion.submit(new Callable<IndexedLaunchOffsetCalc>() {
                            @Override
                            public IndexedLaunchOffsetCalc call() {
                                LaunchOffsetCalcResult calc = analyzeOffset(
                                        oi,
                                        target.baseLaunchJ2000Sec,
                                        target.input.targetId,
                                        target.input.targetName,
                                        target.input.csvPath,
                                        target.baseLaunchUtc,
                                        startOffsetSec,
                                        stepSizeSec,
                                        satellites,
                                        samples,
                                        sensorFovDeg,
                                        activeModel,
                                        target.sourceReferenceEpochSec,
                                        activeSensorConfig,
                                        includeTrajectoryPoints,
                                        sharedProvider);
                                return new IndexedLaunchOffsetCalc(ti, oi, calc);
                            }
                        });
                        submitted++;
                    }
                }
                for (int done = 0; done < submitted; done++) {
                    try {
                        IndexedLaunchOffsetCalc indexed = completion.take().get();
                        perTargetResults[indexed.targetIndex][indexed.offsetIndex] = indexed.calc;
                        if (progressCallback != null) {
                            progressCallback.onProgress(done + 1L, totalProgress);
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("多目标可见性计算被中断。", ex);
                    } catch (Exception ex) {
                        throw new IllegalStateException("多目标可见性计算失败：" + ex.getMessage(), ex);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }

        List<LaunchOffsetCalcResult> allResults = new ArrayList<LaunchOffsetCalcResult>();
        LaunchOffsetCalcResult best = null;
        for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
            LaunchOffsetCalcResult merged = new LaunchOffsetCalcResult();
            // 多目标合并时外层发射时间保留第一个目标的兼容展示值；
            // 每个目标、每个卫星弧段的真实时间写在弧段自身字段中。
            LaunchOffsetCalcResult first = perTargetResults[0][offsetIndex];
            merged.offsetSecond = first.offsetSecond;
            merged.launchTimeUtc = first.launchTimeUtc;
            merged.launchTimeJ2000Sec = first.launchTimeJ2000Sec;
            merged.sourceReferenceEpochSec = first.sourceReferenceEpochSec;
            merged.payloadType = first.payloadType;
            merged.visibilityConstraint = first.visibilityConstraint;

            for (int targetIndex = 0; targetIndex < perTargetResults.length; targetIndex++) {
                LaunchOffsetCalcResult targetCalc = perTargetResults[targetIndex][offsetIndex];
                merged.targets.addAll(targetCalc.targets);
                merged.visibleSatellites.addAll(targetCalc.visibleSatellites);
            }
            allResults.add(merged);
            if (best == null || merged.totalObservableDurationSec() > best.totalObservableDurationSec()) {
                best = merged;
            }
        }

        return new LaunchSearchResultV2(
                allResults,
                best,
                firstReferenceUtc == null ? "" : firstReferenceUtc,
                hasStandardPayloads(satellites) ? firstVisibilityConstraint(allResults, best)
                        : VisibilityConstraintSummary.fromConfig(activeSensorConfig));
    }

    private static void runOffsetSearch(final int targetIndex, final PreparedBallisticTarget target,
            final double baseLaunchJ2000Sec, final String baseLaunchUtc, final long startOffsetSec,
            final long stepSizeSec, final int offsetCount, final List<OrbitElements> satellites,
            final List<BallisticPoint> samples, final double sensorFovDeg, final OrbitModel activeModel,
            final double sourceReferenceEpochSec, final SensorVisibilityConfig sensorConfig,
            final boolean includeTrajectoryPoints, final SatellitePositionProvider satellitePositionProvider,
            final LaunchOffsetCalcResult[] results, final ProgressCallback progressCallback) {
        int threadCount = recommendedSearchThreadCount(offsetCount);
        final String targetId = target == null ? "TGT-001" : target.input.targetId;
        final String targetName = target == null ? "TGT-001" : target.input.targetName;
        final String csvPath = target == null ? null : target.input.csvPath;

        if (threadCount <= 1) {
            for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                results[offsetIndex] = analyzeOffset(
                        offsetIndex,
                        baseLaunchJ2000Sec,
                        targetId,
                        targetName,
                        csvPath,
                        baseLaunchUtc,
                        startOffsetSec,
                        stepSizeSec,
                        satellites,
                        samples,
                        sensorFovDeg,
                        activeModel,
                        sourceReferenceEpochSec,
                        sensorConfig,
                        includeTrajectoryPoints,
                        satellitePositionProvider);
                if (progressCallback != null) {
                    progressCallback.onProgress(offsetIndex + 1L, offsetCount);
                }
            }
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CompletionService<IndexedLaunchOffsetCalc> completion =
                    new ExecutorCompletionService<IndexedLaunchOffsetCalc>(executor);
            for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                final int oi = offsetIndex;
                completion.submit(new Callable<IndexedLaunchOffsetCalc>() {
                    @Override
                    public IndexedLaunchOffsetCalc call() {
                        LaunchOffsetCalcResult calc = analyzeOffset(
                                oi,
                                baseLaunchJ2000Sec,
                                targetId,
                                targetName,
                                csvPath,
                                baseLaunchUtc,
                                startOffsetSec,
                                stepSizeSec,
                                satellites,
                                samples,
                                sensorFovDeg,
                                activeModel,
                                sourceReferenceEpochSec,
                                sensorConfig,
                                includeTrajectoryPoints,
                                satellitePositionProvider);
                        return new IndexedLaunchOffsetCalc(targetIndex, oi, calc);
                    }
                });
            }
            for (int done = 0; done < offsetCount; done++) {
                try {
                    IndexedLaunchOffsetCalc indexed = completion.take().get();
                    results[indexed.offsetIndex] = indexed.calc;
                    if (progressCallback != null) {
                        progressCallback.onProgress(done + 1L, offsetCount);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("偏移量可见性计算被中断。", ex);
                } catch (Exception ex) {
                    throw new IllegalStateException("偏移量可见性计算失败：" + ex.getMessage(), ex);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static LaunchOffsetCalcResult analyzeOffset(int offsetIndex, double baseLaunchJ2000Sec,
            String targetId, String targetName, String csvPath, String baseLaunchUtc, long startOffsetSec,
            long stepSizeSec, List<OrbitElements> satellites, List<BallisticPoint> samples, double sensorFovDeg,
            OrbitModel activeModel, double sourceReferenceEpochSec, SensorVisibilityConfig sensorConfig,
            boolean includeTrajectoryPoints, SatellitePositionProvider satellitePositionProvider) {
        long offsetSec = startOffsetSec + (long) offsetIndex * stepSizeSec;
        double launchJ2000 = baseLaunchJ2000Sec + offsetSec;
        String launchUtc = j2000ToUtcMillis(launchJ2000);
        LaunchOffsetCalcResult calc = analyzeLaunchOffsetV2(
                launchJ2000,
                offsetSec,
                launchUtc,
                satellites,
                samples,
                sensorFovDeg,
                activeModel,
                sourceReferenceEpochSec,
                sensorConfig,
                includeTrajectoryPoints,
                satellitePositionProvider);
        decorateCalculationTarget(
                calc,
                isBlank(targetId) ? "TGT-001" : targetId,
                isBlank(targetName) ? (isBlank(targetId) ? "TGT-001" : targetId) : targetName,
                csvPath,
                baseLaunchUtc,
                sourceReferenceEpochSec);
        return calc;
    }

    private static int recommendedSearchThreadCount(long taskCount) {
        if (taskCount <= 1L) {
            return 1;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        int workerLimit = Math.max(1, processors - 1);
        return (int) Math.max(1L, Math.min(taskCount, workerLimit));
    }

    private static int checkedCalculationCount(long numCalculations) {
        if (numCalculations > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("numCalculations过大。");
        }
        return (int) numCalculations;
    }

    private static void validateMultiTargetInputs(List<BallisticTargetInput> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("ballistics不能为空。");
        }
        for (int i = 0; i < targets.size(); i++) {
            BallisticTargetInput input = targets.get(i);
            if (input == null || isBlank(input.csvPath)) {
                throw new IllegalArgumentException("ballistics[" + i + "].csvPath不能为空。");
            }
        }
    }

    private static BallisticTargetInput normalizedTargetInput(BallisticTargetInput input, int index) {
        BallisticTargetInput result = new BallisticTargetInput();
        result.csvPath = input.csvPath == null ? "" : input.csvPath.trim();
        result.baseLaunchUtc = input.baseLaunchUtc == null ? null : input.baseLaunchUtc.trim();
        result.targetId = isBlank(input.targetId) ? "TGT-" + (index + 1) : input.targetId.trim();
        result.targetName = isBlank(input.targetName) ? result.targetId : input.targetName.trim();
        return result;
    }

    private static void decorateCalculationTarget(LaunchOffsetCalcResult calc, String targetId, String targetName,
            String csvPath, String baseLaunchUtc, double sourceReferenceEpochSec) {
        calc.targetId = targetId;
        calc.targetName = targetName;
        calc.ballisticCsvPath = csvPath;
        calc.baseLaunchUTC = baseLaunchUtc;
        calc.sourceReferenceEpochSec = sourceReferenceEpochSec;
        for (SatelliteVisibilityResult arc : calc.visibleSatellites) {
            arc.targetId = targetId;
            arc.targetName = targetName;
            arc.ballisticCsvPath = csvPath;
            arc.baseLaunchUTC = baseLaunchUtc;
            if (isBlank(arc.payloadType)) {
                arc.payloadType = calc.payloadType;
            }
            if (arc.visibilityConstraint == null) {
                arc.visibilityConstraint = calc.visibilityConstraint;
            }
            arc.launchUTC = calc.launchTimeUtc;
            arc.launchTimeJ2000Sec = calc.launchTimeJ2000Sec;
            arc.sourceReferenceEpochSec = sourceReferenceEpochSec;
        }
        calc.targets.clear();
        calc.targets.add(TargetVisibilitySummary.fromCalc(calc));
    }

    /**
     * 对一次确定的发射偏移量，计算所有卫星对弹道目标的可见弧段。
     *
     * samples已由准备阶段按请求间隔插值。每点绝对时刻为launchJ2000Sec + point.tSec，
     * 卫星位置提供器以该绝对时刻从各自根数历元外推；不依赖共享referenceEpochSec字段。
     * sourceReferenceEpochSec独立保留源文件历元，用于结果中的源轨迹时刻追溯。
     *
     * 可见性遍历：按准备好的samples逐点、逐卫星、逐载荷检查视场和遮挡，不在本循环重复插值。
     * 连续可见的轨迹点归入同一弧段(same arcIndex)；不连续则开启新弧段。
     */
    private static LaunchOffsetCalcResult analyzeLaunchOffsetV2(double launchJ2000Sec, long offsetSec,
            String launchUtc, List<OrbitElements> satellites, List<BallisticPoint> samples, double sensorFovDeg,
            OrbitModel orbitModel, double sourceReferenceEpochSec, SensorVisibilityConfig sensorConfig,
            boolean includeTrajectoryPoints, SatellitePositionProvider satellitePositionProvider) {
        LaunchOffsetCalcResult calc = new LaunchOffsetCalcResult();
        calc.targetId = "TGT-001";
        calc.targetName = "TGT-001";
        calc.launchTimeUtc = launchUtc;
        calc.launchTimeJ2000Sec = launchJ2000Sec;
        calc.sourceReferenceEpochSec = sourceReferenceEpochSec;
        calc.offsetSecond = offsetSec;
        calc.visibilityConstraint = VisibilityConstraintSummary.fromConfig(sensorConfig);
        calc.payloadType = calc.visibilityConstraint.payloadType;

        // 卫星位置按平台传播一次；同一位置供该平台的所有载荷复用。
        List<List<SatellitePayloadConfig>> payloadsBySatellite =
                new ArrayList<List<SatellitePayloadConfig>>(satellites.size());
        // 标记保存原double秒的rawbits；不装箱，不按索引反查列表或累加时间。
        List<List<SampledStateRuns<Boolean>>> payloadRuns = new ArrayList<List<SampledStateRuns<Boolean>>>();
        int[][] payloadArcIndexes = new int[satellites.size()][];
        SatelliteVisibilityResult[][] payloadCurrent = new SatelliteVisibilityResult[satellites.size()][];
        com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool[] standardTools =
                new com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool[satellites.size()];
        com.runisys.orbit.spacevisibility.domain.geometry.Vector3[] previousLos =
                new com.runisys.orbit.spacevisibility.domain.geometry.Vector3[satellites.size()];
        double[] previousTimes = new double[satellites.size()];
        boolean standardRequest = hasStandardPayloads(satellites);

        for (int i = 0; i < satellites.size(); i++) {
            List<SatellitePayloadConfig> payloads = resolveSatellitePayloads(
                    satellites.get(i), sensorConfig, sensorFovDeg, i + 1);
            payloadsBySatellite.add(payloads);
            List<SampledStateRuns<Boolean>> runs = new ArrayList<SampledStateRuns<Boolean>>();
            for (int p = 0; p < payloads.size(); p++) runs.add(new SampledStateRuns<Boolean>(null, 0L));
            payloadRuns.add(runs);
            payloadArcIndexes[i] = new int[payloads.size()];
            payloadCurrent[i] = new SatelliteVisibilityResult[payloads.size()];
            for (SatellitePayloadConfig payload : payloads) {
                if (payload.sensorConfig.standardConstraint != null && standardTools[i] == null) {
                    OrbitElements e = satellites.get(i);
                    standardTools[i] = BallisticOrbitPropagation.bind(new OrbitDefinition.Elements(
                            OrbitDefinition.Model.valueOf(orbitModel.name()), e.semiMajorAxisM, e.eccentricity,
                            e.inclinationDeg, e.raanDeg, e.argumentOfPerigeeDeg, e.meanAnomalyDeg,
                            OrbitTime.legacyJ2000(e.epochSec, utcOffsetSec), 0.0));
                }

            }
        }

        if (standardRequest) {
            calc.visibilityConstraint = VisibilityConstraintSummary.fromConfig(payloadsBySatellite.get(0).get(0).sensorConfig);
            calc.payloadType = calc.visibilityConstraint.payloadType;
        }

        for (BallisticPoint point : samples) {
            double absoluteTime = launchJ2000Sec + point.tSec;
            Vector3 target = ballisticPointToEciAtTime(point, absoluteTime);

            for (int i = 0; i < satellites.size(); i++) {
                OrbitElements elements = satellites.get(i);
                Vector3 satellite = satellitePositionProvider.position(i, elements, absoluteTime, orbitModel);
                List<SatellitePayloadConfig> payloads = payloadsBySatellite.get(i);
                com.runisys.orbit.spacevisibility.domain.geometry.Vector3 standardVelocity = standardTools[i] == null ? null
                        : standardTools[i].velocityAt(OrbitTime.legacyJ2000(absoluteTime, utcOffsetSec), OrbitDefinition.Frame.J2000);
                com.runisys.orbit.spacevisibility.domain.geometry.Vector3 currentLos = standardTools[i] == null ? null
                        : toDomainVector(target.subtract(satellite));
                for (int payloadIndex = 0; payloadIndex < payloads.size(); payloadIndex++) {
                    SatellitePayloadConfig payload = payloads.get(payloadIndex);
                    SensorVisibilityConfig payloadConfig = payload.sensorConfig;
                    SampledStateRuns<Boolean> run = payloadRuns.get(i).get(payloadIndex);
                    boolean wasVisible = run.getState() != null;
                    boolean visible = payloadConfig.standardConstraint == null
                            ? isTargetObservableFromSatelliteStable(satellite, target, payloadConfig, absoluteTime, wasVisible)
                            : isStandardTargetObservable(satellite, standardVelocity, target, payloadConfig.standardConstraint,
                                    absoluteTime, previousLos[i], point.tSec - previousTimes[i]);
                    SampledStateRuns.Segment<Boolean> completed = run.accept(
                            visible ? Boolean.TRUE : null, Double.doubleToRawLongBits(point.tSec));

                    if (visible && !wasVisible) {
                        payloadArcIndexes[i][payloadIndex]++;
                        SatelliteVisibilityResult arc = new SatelliteVisibilityResult();
                        arc.satelliteId = isBlank(elements.satelliteId) ? elements.name : elements.satelliteId;
                        arc.satelliteName = elements.name;
                        arc.satelliteIndex = i + 1;
                        arc.arcIndex = payloadArcIndexes[i][payloadIndex];
                        arc.payloadId = payload.payloadId;
                        arc.payloadName = payload.payloadName;
                        arc.targetId = calc.targetId;
                        arc.targetName = calc.targetName;
                        arc.payloadType = VisibilityConstraintSummary.fromConfig(payloadConfig).payloadType;
                        arc.visibilityConstraint = VisibilityConstraintSummary.fromConfig(payloadConfig);
                        arc.launchUTC = calc.launchTimeUtc;
                        arc.launchTimeJ2000Sec = calc.launchTimeJ2000Sec;
                        arc.sourceReferenceEpochSec = calc.sourceReferenceEpochSec;
                        payloadCurrent[i][payloadIndex] = arc;
                    }

                    if (visible) {
                        Vector3 rEcef = eciToEcefPos(target, absoluteTime);
                        double[] geo = ecefToGeodetic(rEcef);
                        recordVisiblePoint(
                                payloadCurrent[i][payloadIndex],
                                geo[1],
                                geo[0],
                                geo[2],
                                absoluteTime,
                                point.tSec,
                                includeTrajectoryPoints);
                        // metadata来自共用段记录，轨迹关闭时仍有原点数及首末double时间。
                        SatelliteVisibilityResult arc = payloadCurrent[i][payloadIndex];
                        arc.startRelativeTimeSec = Double.longBitsToDouble(run.getStart());
                        arc.endRelativeTimeSec = Double.longBitsToDouble(run.getLast());
                        arc.visiblePointCount = run.getPointCount();
                    }

                    if (completed != null) {
                        calc.visibleSatellites.add(payloadCurrent[i][payloadIndex]);
                        payloadCurrent[i][payloadIndex] = null;
                    }
                }
                // 每目标/星/偏移拥有独立历史；不可见点也更新，速率用实际相邻采样秒。
                previousLos[i] = currentLos;
                previousTimes[i] = point.tSec;
            }
        }

        for (int i = 0; i < satellites.size(); i++) {
            for (int payloadIndex = 0; payloadIndex < payloadRuns.get(i).size(); payloadIndex++) {
                SatelliteVisibilityResult arc = payloadCurrent[i][payloadIndex];
                if (payloadRuns.get(i).get(payloadIndex).getState() != null && arc != null && hasVisiblePoints(arc)) {
                    calc.visibleSatellites.add(arc);
                }
            }
        }
        List<SatelliteVisibilityResult> stableArcs = stableVisibleArcs(
                calc.visibleSatellites,
                MIN_STABLE_VISIBLE_POINT_COUNT,
                MIN_STABLE_VISIBLE_DURATION_SEC);
        calc.visibleSatellites.clear();
        calc.visibleSatellites.addAll(stableArcs);
        return calc;
    }

    /**
     * 解析平台有效载荷。未配置 payloads 时创建一个兼容旧调用的逻辑载荷；
     * 配置了 payloads 时，每个载荷均保留独立的可见性约束。
     */
    private static List<SatellitePayloadConfig> resolveSatellitePayloads(OrbitElements satellite,
            SensorVisibilityConfig fallbackConfig, double sensorFovDeg, int satelliteIndex) {
        List<SatellitePayloadConfig> resolved = new ArrayList<SatellitePayloadConfig>();
        List<SatellitePayloadConfig> configured = satellite == null ? null : satellite.payloads;
        if (configured == null || configured.isEmpty()) {
            SatellitePayloadConfig legacy = new SatellitePayloadConfig();
            legacy.payloadId = "LEGACY-" + satelliteIndex;
            legacy.sensorConfig = normalizeSensorConfig(fallbackConfig, sensorFovDeg);
            legacy.payloadName = legacy.sensorConfig.payloadType.displayName();
            resolved.add(legacy);
            return resolved;
        }

        for (int payloadIndex = 0; payloadIndex < configured.size(); payloadIndex++) {
            SatellitePayloadConfig source = configured.get(payloadIndex);
            if (source == null) {
                throw new IllegalArgumentException("卫星" + satelliteIndex + "的payloads[" + payloadIndex + "]不能为空。");
            }
            SatellitePayloadConfig payload = new SatellitePayloadConfig();
            payload.sensorConfig = normalizeSensorConfig(
                    source.sensorConfig == null ? fallbackConfig : source.sensorConfig,
                    sensorFovDeg);
            payload.payloadId = isBlank(source.payloadId)
                    ? (isBlank(satellite.name) ? "SAT-" + satelliteIndex : satellite.name)
                    + "-PAYLOAD-" + (payloadIndex + 1)
                    : source.payloadId.trim();
            payload.payloadName = isBlank(source.payloadName)
                    ? VisibilityConstraintSummary.fromConfig(payload.sensorConfig).payloadType
                    : source.payloadName.trim();
            resolved.add(payload);
        }
        return resolved;
    }

    private static void validateSearchInputsV2(long stepSizeSec, long numCalculations,
            List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints, double sensorFovDeg) {
        if (stepSizeSec <= 0L) throw new IllegalArgumentException("发射偏移步长必须大于0秒。");
        if (numCalculations <= 0L) throw new IllegalArgumentException("计算次数必须大于0。");
        if (sensorFovDeg <= 0.0 || sensorFovDeg > 180.0)
            throw new IllegalArgumentException("传感器视场角应在0到180度之间。");
        if (ballisticPoints == null || ballisticPoints.isEmpty())
            throw new IllegalArgumentException("弹道点不能为空。");
        if (satellites == null || satellites.isEmpty())
            throw new IllegalArgumentException("请至少输入一颗卫星。");
        for (OrbitElements elements : satellites) validateOrbit(elements);
    }

    /** 是否显式使用标准载荷；旧纯中文请求保持原空结果及根摘要规则。 */
    private static boolean hasStandardPayloads(List<OrbitElements> satellites) {
        for (OrbitElements satellite : satellites) {
            if (satellite.payloads == null) continue;
            for (SatellitePayloadConfig payload : satellite.payloads) {
                if (payload != null && payload.sensorConfig != null && payload.sensorConfig.standardConstraint != null) return true;
            }
        }
        return false;
    }
}
