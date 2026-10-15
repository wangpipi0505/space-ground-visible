package com.runisys.orbit.spacevisibility.domain.ballistic;

import java.util.ArrayList;
import java.util.List;

import static com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator.*;

/**
 * 可见弧段的统一统计与摘要工具，供搜索、公开结果类型和输出使用。
 * 保留采样首末点、单点零连续时长、metadata-only回退及有序累计规则；时间单位为秒。
 */
final class BallisticArcSupport {
    private BallisticArcSupport() {
    }

    static VisibilityConstraintSummary firstVisibilityConstraint(List<LaunchOffsetCalcResult> allResults,
            LaunchOffsetCalcResult best) {
        if (best != null && best.visibilityConstraint != null) {
            return best.visibilityConstraint;
        }
        if (allResults != null) {
            for (LaunchOffsetCalcResult result : allResults) {
                if (result != null && result.visibilityConstraint != null) {
                    return result.visibilityConstraint;
                }
            }
        }
        return VisibilityConstraintSummary.fromConfig(SensorVisibilityConfig.forPayload(
                PayloadType.GROUND_SURVEILLANCE));
    }

    static TimeWindowSummary finalVisibleWindow(List<SatelliteVisibilityResult> arcs) {
        double start = Double.POSITIVE_INFINITY;
        double end = Double.NEGATIVE_INFINITY;
        for (SatelliteVisibilityResult arc : arcs) {
            if (!hasVisiblePoints(arc)) {
                continue;
            }
            start = Math.min(start, arcStartJ2000Sec(arc));
            end = Math.max(end, arcEndJ2000Sec(arc));
        }
        if (Double.isInfinite(start) || Double.isInfinite(end)) {
            return null;
        }
        return new TimeWindowSummary(start, end);
    }

    static TimeWindowSummary finalVisibleWindowFromWindows(List<VisibleWindowSummary> windows) {
        double start = Double.POSITIVE_INFINITY;
        double end = Double.NEGATIVE_INFINITY;
        for (VisibleWindowSummary window : windows) {
            if (window.pointCount <= 0) {
                continue;
            }
            start = Math.min(start, window.startJ2000Sec);
            end = Math.max(end, window.endJ2000Sec);
        }
        if (Double.isInfinite(start) || Double.isInfinite(end)) {
            return null;
        }
        return new TimeWindowSummary(start, end);
    }

    static double sourceReferenceFor(SatelliteVisibilityResult arc) {
        if (arc != null && !Double.isNaN(arc.sourceReferenceEpochSec)) {
            return arc.sourceReferenceEpochSec;
        }
        return referenceEpochSec;
    }

    static double sourceReferenceFor(SatelliteVisibilityResult arc, LaunchOffsetCalcResult calc) {
        if (arc != null && !Double.isNaN(arc.sourceReferenceEpochSec)) {
            return arc.sourceReferenceEpochSec;
        }
        if (calc != null && !Double.isNaN(calc.sourceReferenceEpochSec)) {
            return calc.sourceReferenceEpochSec;
        }
        return referenceEpochSec;
    }

    static boolean hasVisiblePoints(SatelliteVisibilityResult arc) {
        return arcPointCount(arc) > 0;
    }

    static List<SatelliteVisibilityResult> stableVisibleArcs(List<SatelliteVisibilityResult> arcs,
            int minPointCount, double minDurationSec) {
        List<SatelliteVisibilityResult> stable = new ArrayList<SatelliteVisibilityResult>();
        if (arcs == null || arcs.isEmpty()) {
            return stable;
        }
        int requiredPoints = Math.max(1, minPointCount);
        double requiredDuration = Math.max(0.0, minDurationSec);
        for (SatelliteVisibilityResult arc : arcs) {
            if (arc == null) {
                continue;
            }
            int pointCount = arcPointCount(arc);
            double durationSec = arcDurationSec(arc);
            // 单点或极短弧段通常来自视场边界附近的采样抖动，默认不作为稳定窗口输出。
            if (pointCount >= requiredPoints && durationSec + 1e-9 >= requiredDuration) {
                stable.add(arc);
            }
        }
        return stable;
    }

    static int arcPointCount(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return 0;
        }
        if (arc.visiblePointCount > 0) {
            return arc.visiblePointCount;
        }
        return arc.trajectoryPoints.size();
    }

    static double arcStartJ2000Sec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.startJ2000Sec)) {
            return arc.startJ2000Sec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN : arc.trajectoryPoints.get(0).absoluteTimeSec;
    }

    static double arcEndJ2000Sec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.endJ2000Sec)) {
            return arc.endJ2000Sec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN
                : arc.trajectoryPoints.get(arc.trajectoryPoints.size() - 1).absoluteTimeSec;
    }

    static double arcStartRelativeTimeSec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.startRelativeTimeSec)) {
            return arc.startRelativeTimeSec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN : arc.trajectoryPoints.get(0).relativeTimeSec;
    }

    static double arcEndRelativeTimeSec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.endRelativeTimeSec)) {
            return arc.endRelativeTimeSec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN
                : arc.trajectoryPoints.get(arc.trajectoryPoints.size() - 1).relativeTimeSec;
    }

    static double arcDurationSec(SatelliteVisibilityResult arc) {
        if (!hasVisiblePoints(arc)) {
            return 0.0;
        }
        double start = arcStartRelativeTimeSec(arc);
        double end = arcEndRelativeTimeSec(arc);
        if (Double.isNaN(start) || Double.isNaN(end)) {
            return arcDurationSec(arc.trajectoryPoints);
        }
        return Math.max(0.0, end - start);
    }

    static double arcDurationSec(List<VisibleTrajectoryPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0.0;
        }
        if (points.size() == 1) {
            return 0.0;
        }
        return Math.max(0.0,
                points.get(points.size() - 1).relativeTimeSec - points.get(0).relativeTimeSec);
    }
}
