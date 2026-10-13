package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.window.CombinationConstraint;
import com.runisys.orbit.spacevisibility.domain.window.CombinationWindow;
import com.runisys.orbit.spacevisibility.domain.window.CombinationWindowCalculator;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import com.runisys.orbit.spacevisibility.domain.window.WindowNormalizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将单星对单目标的观测弧段按目标归集，再计算单星或多星对同一目标的筛选结果。
 */
public final class VisibilityConstraintPlanner {

    /**
     * 仅在同一目标内对参与卫星的观测弧段求交集，并按筛选约束过滤最小时长。
     *
     * <p>管线分五步，任何一步都不改变单星弧段的原始边界：</p>
     * <ol>
     *   <li><b>归集</b>：把 N 个“单星-单目标”结果按 目标 → 卫星 归并成窗口/弧段两级索引；</li>
     *   <li><b>归一化</b>：每颗星的窗口先合并相邻碎片（光照状态切换会把一段连续可见切成多条），
     *       保证最小时长按连续允许可见性判定，而不是按状态子段判定；</li>
     *   <li><b>组合交集</b>：对每条约束（如 A+B+C ≥400s）求成员卫星窗口的时间交集，
     *       得到若干共同可见片段；</li>
     *   <li><b>累计验收</b>：交集片段时长之和 ≥ 门槛即整组通过（与 WinTool 的 FREGAT DEB
     *       两段累计案例一致），通过后每个片段独立输出，不把中间空档拼成连续弧段；</li>
     *   <li><b>结果组装</b>：以共同窗口内各星原始弧段边界切出光照状态稳定的子段，相邻同状态
     *       子段合并；再为每颗成员星回填覆盖共同窗口的原始连续窗口（satelliteSegments），
     *       供前端展开“每颗星的独立起止与时长”。</li>
     * </ol>
     *
     * @param planningStart 本次规划起始时刻
     * @param pairResults 单星对单目标的原始观测结果
     * @param constraints 用户选择的单星或多星观测筛选约束
     * @return 按目标和卫星组合稳定排序的有效观测结果
     */
    public List<VisibilityObservationResult> calculate(Instant planningStart,
                                                        Collection<VisibilityPairResult> pairResults,
                                                        Collection<CombinationConstraint> constraints) {
        Map<String, Map<String, List<TimeInterval>>> windowsByTarget = new HashMap<String, Map<String, List<TimeInterval>>>();
        Map<String, Map<String, List<VisibilityArc>>> arcsByTarget = new HashMap<String, Map<String, List<VisibilityArc>>>();
        // 观测统计索引：目标 → 卫星 → 该“星-目标”组合在规划时段内的极值统计。
        Map<String, Map<String, VisibilityPairStatistics>> statisticsByTarget =
                new HashMap<String, Map<String, VisibilityPairStatistics>>();
        for (VisibilityPairResult pairResult : pairResults) {
            Map<String, List<TimeInterval>> windowsBySatellite = windowsByTarget.computeIfAbsent(
                    pairResult.getTargetId(), key -> new HashMap<String, List<TimeInterval>>());
            List<TimeInterval> intervals = windowsBySatellite.computeIfAbsent(
                    pairResult.getObserverSatelliteId(), key -> new ArrayList<TimeInterval>());
            List<VisibilityArc> arcs = arcsByTarget.computeIfAbsent(pairResult.getTargetId(),
                    key -> new HashMap<String, List<VisibilityArc>>()).computeIfAbsent(
                    pairResult.getObserverSatelliteId(), key -> new ArrayList<VisibilityArc>());
            for (VisibilityArc arc : pairResult.getArcs()) {
                intervals.add(TimeInterval.of(secondsFrom(planningStart, arc.getStartTime()),
                        secondsFrom(planningStart, arc.getEndTime())));
                arcs.add(arc);
            }
            if (pairResult.getStatistics() != null) {
                statisticsByTarget.computeIfAbsent(pairResult.getTargetId(),
                        key -> new HashMap<String, VisibilityPairStatistics>())
                        .put(pairResult.getObserverSatelliteId(), pairResult.getStatistics());
            }
        }

        List<VisibilityObservationResult> results = new ArrayList<VisibilityObservationResult>();
        for (Map.Entry<String, Map<String, List<TimeInterval>>> targetEntry : windowsByTarget.entrySet()) {
            for (CombinationConstraint constraint : constraints) {
                Map<String, List<TimeInterval>> normalizedWindows = normalizeWindows(targetEntry.getValue());
                List<CombinationWindow> combinationWindows = CombinationWindowCalculator.calculate(
                        normalizedWindows, constraint);
                if (!combinationWindows.isEmpty()) {
                    List<VisibilityArc> arcs = toArcs(planningStart, combinationWindows,
                            arcsByTarget.get(targetEntry.getKey()), constraint.getSatelliteIds(),
                            statisticsByTarget.get(targetEntry.getKey()));
                    // 单星约束把统计挂在弧段上（无 satelliteSegments 可挂），保持输出形状不变。
                    Map<String, VisibilityPairStatistics> statisticsBySatellite =
                            statisticsByTarget.get(targetEntry.getKey());
                    if (constraint.getSatelliteIds().size() == 1 && statisticsBySatellite != null) {
                        VisibilityPairStatistics statistics =
                                statisticsBySatellite.get(constraint.getSatelliteIds().get(0));
                        if (statistics != null) {
                            List<VisibilityArc> withStatistics = new ArrayList<VisibilityArc>(arcs.size());
                            for (VisibilityArc arc : arcs) {
                                withStatistics.add(arc.withPairStatistics(statistics));
                            }
                            arcs = withStatistics;
                        }
                    }
                    results.add(new VisibilityObservationResult(targetEntry.getKey(), constraint.getSatelliteIds(),
                            arcs));
                }
            }
        }
        results.sort(Comparator.comparing(VisibilityObservationResult::getTargetId)
                .thenComparing(result -> String.join(",", result.getObserverSatelliteIds())));
        return results;
    }

    /** 将相对规划起点的秒偏移恢复为 UTC 观测弧段，并为每颗参与星附带其观测统计。 */
    private List<VisibilityArc> toArcs(Instant planningStart,
                                       List<CombinationWindow> windows,
                                       Map<String, List<VisibilityArc>> arcsBySatellite,
                                       List<String> observerSatelliteIds,
                                       Map<String, VisibilityPairStatistics> statisticsBySatellite) {
        List<VisibilityArc> arcs = new ArrayList<VisibilityArc>();
        for (CombinationWindow window : windows) {
            TimeInterval interval = window.getInterval();
            List<Double> boundaries = boundaries(interval, arcsBySatellite, observerSatelliteIds, planningStart);
            List<VisibilityArc> illuminationSegments = new ArrayList<VisibilityArc>();
            for (int index = 0; index < boundaries.size() - 1; index++) {
                double startSec = boundaries.get(index);
                double endSec = boundaries.get(index + 1);
                if (endSec > startSec) {
                    illuminationSegments.add(toIlluminatedArc(planningStart, startSec, endSec,
                            arcsBySatellite, observerSatelliteIds));
                }
            }
            List<VisibilityArc> stableSegments = mergeAdjacentSameStateArcs(illuminationSegments);
            if (!stableSegments.isEmpty()) {
                VisibilityArc firstSegment = stableSegments.get(0);
                arcs.add(new VisibilityArc(atOffset(planningStart, interval.getStartSec()),
                        atOffset(planningStart, interval.getEndSec()), firstSegment.getObserverIlluminations(),
                        firstSegment.getTargetIllumination(), toIlluminationSegments(stableSegments))
                        .withSatelliteSegments(sourceSatelliteSegments(planningStart, interval,
                                arcsBySatellite, observerSatelliteIds, statisticsBySatellite)));
            }
        }
        return arcs;
    }

    /**
     * 为一个共同观测窗口找回每颗星覆盖它的原始连续单星窗口。
     *
     * <p>例如 A 为 0s..917s、C 为 563.118s..917s 时，AC 的共同窗口是 563.118s..917s；
     * 页面展开信息必须返回 A 的 0s..917s 和 C 的 563.118s..917s，不能把交集范围重复填给两颗星。</p>
     */
    private List<VisibilityArc.SatelliteSegment> sourceSatelliteSegments(Instant planningStart,
                                                                           TimeInterval commonWindow,
                                                                           Map<String, List<VisibilityArc>> arcsBySatellite,
                                                                           List<String> observerSatelliteIds,
                                                                           Map<String, VisibilityPairStatistics> statisticsBySatellite) {
        if (observerSatelliteIds.size() < 2) {
            return java.util.Collections.emptyList();
        }
        List<VisibilityArc.SatelliteSegment> segments = new ArrayList<VisibilityArc.SatelliteSegment>();
        for (String satelliteId : observerSatelliteIds) {
            VisibilityPairStatistics statistics =
                    statisticsBySatellite == null ? null : statisticsBySatellite.get(satelliteId);
            segments.add(sourceSatelliteSegment(planningStart, commonWindow, satelliteId,
                    arcsBySatellite.get(satelliteId), statistics));
        }
        return segments;
    }

    /** 按连续覆盖关系合并同一颗星因光照状态变化而拆分的原始弧段，并找出覆盖共同窗口的一组。 */
    private VisibilityArc.SatelliteSegment sourceSatelliteSegment(Instant planningStart,
                                                                    TimeInterval commonWindow,
                                                                    String satelliteId,
                                                                    List<VisibilityArc> sourceArcs,
                                                                    VisibilityPairStatistics statistics) {
        if (sourceArcs == null || sourceArcs.isEmpty()) {
            throw new IllegalStateException("组合窗口缺少参与卫星的原始观测弧段: " + satelliteId);
        }
        List<VisibilityArc> ordered = new ArrayList<VisibilityArc>(sourceArcs);
        ordered.sort(Comparator.comparing(VisibilityArc::getStartTime));
        List<VisibilityArc> contiguous = new ArrayList<VisibilityArc>();
        double startSec = 0.0;
        double endSec = 0.0;
        for (VisibilityArc sourceArc : ordered) {
            double candidateStart = secondsFrom(planningStart, sourceArc.getStartTime());
            double candidateEnd = secondsFrom(planningStart, sourceArc.getEndTime());
            if (contiguous.isEmpty()) {
                contiguous.add(sourceArc);
                startSec = candidateStart;
                endSec = candidateEnd;
                continue;
            }
            // 1 纳秒容差只消除浮点换算噪声，不会把真实不可见间隔当作连续窗口。
            if (candidateStart <= endSec + 1.0e-9) {
                contiguous.add(sourceArc);
                endSec = Math.max(endSec, candidateEnd);
                continue;
            }
            if (covers(commonWindow, startSec, endSec)) {
                return satelliteSegment(satelliteId, contiguous, planningStart, startSec, endSec, statistics);
            }
            contiguous.clear();
            contiguous.add(sourceArc);
            startSec = candidateStart;
            endSec = candidateEnd;
        }
        if (covers(commonWindow, startSec, endSec)) {
            return satelliteSegment(satelliteId, contiguous, planningStart, startSec, endSec, statistics);
        }
        throw new IllegalStateException("组合窗口未被参与卫星原始观测弧段完整覆盖: " + satelliteId);
    }

    private boolean covers(TimeInterval commonWindow, double startSec, double endSec) {
        return startSec <= commonWindow.getStartSec() + 1.0e-9
                && endSec + 1.0e-9 >= commonWindow.getEndSec();
    }

    /** 生成单星原始窗口。若连续窗口内光照发生变化，明确返回“状态变化”而不伪造某一个状态。 */
    private VisibilityArc.SatelliteSegment satelliteSegment(String satelliteId,
                                                             List<VisibilityArc> sourceArcs,
                                                             Instant planningStart,
                                                             double startSec,
                                                             double endSec,
                                                             VisibilityPairStatistics statistics) {
        VisibilityArc.ObserverIllumination firstObserver = null;
        boolean illuminationChanged = false;
        for (VisibilityArc sourceArc : sourceArcs) {
            for (VisibilityArc.ObserverIllumination observer : sourceArc.getObserverIlluminations()) {
                if (!satelliteId.equals(observer.getSatelliteId())) {
                    continue;
                }
                if (firstObserver == null) {
                    firstObserver = observer;
                } else if (!firstObserver.getState().equals(observer.getState())) {
                    illuminationChanged = true;
                }
            }
        }
        String satelliteName = firstObserver == null ? satelliteId : firstObserver.getSatelliteName();
        String state = firstObserver == null ? null : firstObserver.getState();
        String stateName = firstObserver == null ? null : firstObserver.getStateName();
        if (illuminationChanged) {
            state = "MULTIPLE";
            stateName = "状态变化";
        }
        return new VisibilityArc.SatelliteSegment(satelliteId, satelliteName, state, stateName,
                atOffset(planningStart, startSec), atOffset(planningStart, endSec), endSec - startSec,
                statistics);
    }

    /** 将光照稳定子片段附属到已通过门槛的连续父窗口，避免子片段被错误当成独立结果。 */
    private List<VisibilityArc.IlluminationSegment> toIlluminationSegments(List<VisibilityArc> stableSegments) {
        List<VisibilityArc.IlluminationSegment> segments = new ArrayList<VisibilityArc.IlluminationSegment>();
        for (VisibilityArc segment : stableSegments) {
            segments.add(new VisibilityArc.IlluminationSegment(segment.getStartTime(), segment.getEndTime(),
                    segment.getObserverIlluminations(), segment.getTargetIllumination()));
        }
        return segments;
    }

    /**
     * 合并首尾相接且光照状态完全一致的相邻弧段。扫描网格与单星窗口边界会把同一连续观测时段
     * 切成多条子弧（如 00:54:21-00:55:36 与 00:55:36-01:04:40），WinTool 按连续时段展示，
     * 故同状态的相接弧段在输出前合并；光照状态真正变化时仍按状态分段。
     */
    private List<VisibilityArc> mergeAdjacentSameStateArcs(List<VisibilityArc> rawArcs) {
        rawArcs.sort(Comparator.comparing(VisibilityArc::getStartTime));
        List<VisibilityArc> merged = new ArrayList<VisibilityArc>();
        for (VisibilityArc arc : rawArcs) {
            if (!merged.isEmpty()) {
                VisibilityArc previous = merged.get(merged.size() - 1);
                if (previous.getEndTime().equals(arc.getStartTime()) && sameIllumination(previous, arc)) {
                    merged.set(merged.size() - 1, new VisibilityArc(previous.getStartTime(), arc.getEndTime(),
                            previous.getObserverIlluminations(), previous.getTargetIllumination()));
                    continue;
                }
            }
            merged.add(arc);
        }
        return merged;
    }

    /** 两条弧段的观测星光照序列与目标光照是否完全一致。 */
    private static boolean sameIllumination(VisibilityArc first, VisibilityArc second) {
        if (first.getObserverIlluminations().size() != second.getObserverIlluminations().size()) {
            return false;
        }
        for (int index = 0; index < first.getObserverIlluminations().size(); index++) {
            VisibilityArc.ObserverIllumination left = first.getObserverIlluminations().get(index);
            VisibilityArc.ObserverIllumination right = second.getObserverIlluminations().get(index);
            if (!left.getSatelliteId().equals(right.getSatelliteId())
                    || !left.getState().equals(right.getState())) {
                return false;
            }
        }
        if (first.getTargetIllumination() == null || second.getTargetIllumination() == null) {
            return first.getTargetIllumination() == second.getTargetIllumination();
        }
        return first.getTargetIllumination().getTargetId().equals(second.getTargetIllumination().getTargetId())
                && first.getTargetIllumination().getState().equals(second.getTargetIllumination().getState());
    }

    /** 合并状态切换产生的相邻原始窗口，确保最小时长按连续允许可见性而不是按状态段判定。 */
    private Map<String, List<TimeInterval>> normalizeWindows(Map<String, List<TimeInterval>> rawWindows) {
        Map<String, List<TimeInterval>> normalized = new HashMap<String, List<TimeInterval>>();
        for (Map.Entry<String, List<TimeInterval>> entry : rawWindows.entrySet()) {
            normalized.put(entry.getKey(), WindowNormalizer.normalize(entry.getValue(), 0.0, 0.0, 0.0));
        }
        return normalized;
    }

    /** 收集组合窗口及每颗参与卫星原始状态弧段的边界，用于生成状态稳定的结果片段。 */
    private List<Double> boundaries(TimeInterval interval,
                                    Map<String, List<VisibilityArc>> arcsBySatellite,
                                    List<String> observerSatelliteIds,
                                    Instant planningStart) {
        List<Double> boundaries = new ArrayList<Double>();
        boundaries.add(interval.getStartSec());
        boundaries.add(interval.getEndSec());
        for (String satelliteId : observerSatelliteIds) {
            for (VisibilityArc arc : arcsBySatellite.get(satelliteId)) {
                double startSec = secondsFrom(planningStart, arc.getStartTime());
                double endSec = secondsFrom(planningStart, arc.getEndTime());
                if (startSec > interval.getStartSec() && startSec < interval.getEndSec()) {
                    boundaries.add(startSec);
                }
                if (endSec > interval.getStartSec() && endSec < interval.getEndSec()) {
                    boundaries.add(endSec);
                }
            }
        }
        boundaries.sort(Comparator.naturalOrder());
        List<Double> unique = new ArrayList<Double>();
        for (Double boundary : boundaries) {
            if (unique.isEmpty() || Double.compare(unique.get(unique.size() - 1), boundary) != 0) {
                unique.add(boundary);
            }
        }
        return unique;
    }

    /** 在状态稳定的子区间中提取每颗参与卫星和目标的光照详情，兼容旧无状态弧段。 */
    private VisibilityArc toIlluminatedArc(Instant planningStart,
                                           double startSec,
                                           double endSec,
                                           Map<String, List<VisibilityArc>> arcsBySatellite,
                                           List<String> observerSatelliteIds) {
        List<VisibilityArc.ObserverIllumination> observers = new ArrayList<VisibilityArc.ObserverIllumination>();
        VisibilityArc.TargetIllumination target = null;
        for (String satelliteId : observerSatelliteIds) {
            VisibilityArc source = sourceArcAt(arcsBySatellite.get(satelliteId), planningStart, startSec, endSec);
            observers.addAll(source.getObserverIlluminations());
            if (target == null) {
                target = source.getTargetIllumination();
            }
        }
        if (observers.isEmpty() && target == null) {
            return new VisibilityArc(atOffset(planningStart, startSec), atOffset(planningStart, endSec));
        }
        return new VisibilityArc(atOffset(planningStart, startSec), atOffset(planningStart, endSec), observers, target);
    }

    /** 找到完整覆盖当前稳定子区间的原始单星状态弧段。 */
    private VisibilityArc sourceArcAt(List<VisibilityArc> arcs,
                                      Instant planningStart,
                                      double startSec,
                                      double endSec) {
        for (VisibilityArc arc : arcs) {
            double arcStart = secondsFrom(planningStart, arc.getStartTime());
            double arcEnd = secondsFrom(planningStart, arc.getEndTime());
            if (arcStart <= startSec && arcEnd >= endSec) {
                return arc;
            }
        }
        throw new IllegalStateException("组合窗口缺少覆盖当前时段的观测状态");
    }

    /** 计算时刻相对规划起点的秒偏移，保留纳秒精度。 */
    private double secondsFrom(Instant planningStart, Instant instant) {
        return instant.getEpochSecond() - planningStart.getEpochSecond()
                + (instant.getNano() - planningStart.getNano()) / 1_000_000_000.0;
    }

    /** 以纳秒精度将秒偏移叠加到规划起点。 */
    private Instant atOffset(Instant planningStart, double seconds) {
        return planningStart.plusNanos(Math.round(seconds * 1_000_000_000.0));
    }
}
