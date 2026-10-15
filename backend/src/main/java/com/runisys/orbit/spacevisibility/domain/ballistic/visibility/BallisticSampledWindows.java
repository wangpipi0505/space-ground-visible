package com.runisys.orbit.spacevisibility.domain.ballistic.visibility;

import com.runisys.orbit.spacevisibility.domain.window.SampledStateRuns;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticVisibilityGeometry.isTargetObservableFromSatellite;
import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator.*;

/**
 * 基于轨迹采样点的窗口与显示轨迹实现，同时向V2搜索提供排序、线性插值和采样间隔选择。
 * 兼容窗口使用首末原采样点与整秒点计数时长；V2连续弧段仍由BallisticLaunchSearch管理。
 * 插值保持各列逐分量计算、原始端点和浮点运算顺序；不替换传播器或增加重采样。
 */
final class BallisticSampledWindows {
    private BallisticSampledWindows() {
    }

    static List<Track> generateSatelliteTracks(List<OrbitElements> satellites, double startSec, double endSec,
            double stepSec, OrbitModel orbitModel) {
        validatePositive(stepSec, "时间步长");
        if (endSec < startSec) {
            throw new IllegalArgumentException("结束时间不能小于开始时间。");
        }
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;

        List<Track> tracks = new ArrayList<Track>();
        for (OrbitElements elements : satellites) {
            validateOrbit(elements);
            List<TimedPosition> points = new ArrayList<TimedPosition>();
            for (double t = startSec; t <= endSec + 1e-9; t += stepSec) {
                points.add(new TimedPosition(t, propagate(elements, t, activeModel)));
            }
            if (points.isEmpty() || points.get(points.size() - 1).tSec < endSec) {
                points.add(new TimedPosition(endSec, propagate(elements, endSec, activeModel)));
            }
            tracks.add(new Track(elements.name, points));
        }
        return tracks;
    }

    static List<BallisticPoint> interpolateBallistic(List<BallisticPoint> points, double stepSec) {
        validatePositive(stepSec, "弹道插值步长");
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("弹道点不能为空。");
        }
        if (points.size() == 1) {
            return new ArrayList<BallisticPoint>(points);
        }

        List<BallisticPoint> sorted = sortedBallistic(points);
        double start = sorted.get(0).tSec;
        double end = sorted.get(sorted.size() - 1).tSec;
        List<BallisticPoint> result = new ArrayList<BallisticPoint>();
        for (double t = start; t <= end + 1e-9; t += stepSec) {
            result.add(interpolateAt(sorted, t));
        }
        if (result.get(result.size() - 1).tSec < end) {
            result.add(interpolateAt(sorted, end));
        }
        return result;
    }

    static double visibilitySampleStepSec(List<BallisticPoint> sorted, double requestedStepSec) {
        if (!Double.isNaN(requestedStepSec) && !Double.isInfinite(requestedStepSec) && requestedStepSec > 0.0) {
            return Math.max(1e-3, requestedStepSec);
        }
        if (sorted == null || sorted.size() < 2) {
            return 1.0;
        }
        double minDt = Double.POSITIVE_INFINITY;
        for (int i = 0; i < sorted.size() - 1; i++) {
            double dt = sorted.get(i + 1).tSec - sorted.get(i).tSec;
            if (dt > 1e-9) {
                minDt = Math.min(minDt, dt);
            }
        }
        if (Double.isInfinite(minDt)) {
            return 1.0;
        }
        return Math.max(1e-3, Math.min(1.0, minDt));
    }

    static double ballisticStartSec(List<BallisticPoint> ballisticPoints) {
        return sortedBallistic(ballisticPoints).get(0).tSec;
    }

    static double ballisticEndSec(List<BallisticPoint> ballisticPoints) {
        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        return sorted.get(sorted.size() - 1).tSec;
    }

    static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints, double displayStepSec,
            double launchOffsetSec, GeodeticDatum datum) {
        List<BallisticPoint> samples = interpolateBallistic(ballisticPoints, displayStepSec);
        List<TimedPosition> positions = new ArrayList<TimedPosition>();
        for (BallisticPoint point : samples) {
            double absoluteTime = launchOffsetSec + point.tSec;
            positions.add(new TimedPosition(
                    absoluteTime,
                    ballisticPointToEciAtTime(point, referenceEpochSec + absoluteTime)
            ));
        }
        return new Track("弹道轨迹", positions);
    }

    static List<Track> generateVisibleBallisticTracks(List<BallisticPoint> ballisticPoints, double displayStepSec,
            double launchOffsetSec, List<VisibilityWindow> windows, GeodeticDatum datum) {
        List<Track> tracks = new ArrayList<Track>();
        if (windows == null || windows.isEmpty()) {
            return tracks;
        }

        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        for (int index = 0; index < windows.size(); index++) {
            VisibilityWindow window = windows.get(index);
            List<TimedPosition> positions = new ArrayList<TimedPosition>();
            for (double t = window.startRelSec; t <= window.endRelSec + 1e-9; t += displayStepSec) {
                BallisticPoint point = interpolateAt(sorted, t);
                double absoluteTime = launchOffsetSec + point.tSec;
                positions.add(new TimedPosition(
                        absoluteTime,
                        ballisticPointToEciAtTime(point, referenceEpochSec + absoluteTime)
                ));
            }
            if (!positions.isEmpty() && positions.get(positions.size() - 1).tSec < launchOffsetSec + window.endRelSec) {
                BallisticPoint point = interpolateAt(sorted, window.endRelSec);
                double absoluteTime = launchOffsetSec + point.tSec;
                positions.add(new TimedPosition(
                        absoluteTime,
                        ballisticPointToEciAtTime(point, referenceEpochSec + absoluteTime)
                ));
            }
            if (positions.size() >= 2) {
                tracks.add(new Track("可见弹道段-" + (index + 1), positions));
            }
        }
        return tracks;
    }

    static LaunchSearchResult searchLaunchOffsets(long startTimeSec, long stepSizeSec, long numCalculations,
            List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints, double sensorFovDeg,
            GeodeticDatum datum, OrbitModel orbitModel, ProgressCallback progressCallback) {
        validateSearchInputs(numCalculations, stepSizeSec, satellites, ballisticPoints, sensorFovDeg);
        if (stepSizeSec <= 0L) {
            throw new IllegalArgumentException("计算步长必须大于0秒。");
        }
        if (numCalculations <= 0L) {
            throw new IllegalArgumentException("计算次数必须大于0。");
        }
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;

        List<BallisticPoint> samples = interpolateBallistic(ballisticPoints, 1.0);
        List<LaunchOffsetResult> candidates = new ArrayList<LaunchOffsetResult>();
        LaunchOffsetResult best = null;
        long completed = 0L;
        for (long i = 0; i < numCalculations; i++) {
            long offsetSecond = startTimeSec + i * stepSizeSec;
            LaunchOffsetResult candidate = analyzeLaunchOffset(offsetSecond, satellites, samples, sensorFovDeg, datum, activeModel);
            candidates.add(candidate);
            if (best == null || candidate.observableSeconds > best.observableSeconds) {
                best = candidate;
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.onProgress(completed, numCalculations);
            }
        }
        return new LaunchSearchResult(candidates, best);
    }

    private static LaunchOffsetResult analyzeLaunchOffset(long offsetSecond, List<OrbitElements> satellites,
            List<BallisticPoint> samples, double sensorFovDeg, GeodeticDatum datum, OrbitModel orbitModel) {
        List<VisibilityWindow> unionWindows = new ArrayList<VisibilityWindow>();
        List<SatelliteVisibilityWindow> satelliteWindows = new ArrayList<SatelliteVisibilityWindow>();
        boolean[] satelliteInWindow = new boolean[satellites.size()];
        double[] satelliteWindowStart = new double[satellites.size()];
        double[] satelliteLastVisible = new double[satellites.size()];

        boolean unionInWindow = false;
        double unionWindowStart = 0.0;
        double unionLastVisible = 0.0;

        for (BallisticPoint point : samples) {
            double absoluteTime = offsetSecond + point.tSec;
            Vector3 target = ballisticPointToEci(point, offsetSecond);
            boolean anyVisible = false;

            for (int i = 0; i < satellites.size(); i++) {
                OrbitElements elements = satellites.get(i);
                Vector3 satellite = propagate(elements, absoluteTime, orbitModel);
                boolean visible = isTargetObservableFromSatellite(
                        satellite, target, sensorFovDeg, referenceEpochSec + absoluteTime);
                anyVisible = anyVisible || visible;

                if (visible && !satelliteInWindow[i]) {
                    satelliteInWindow[i] = true;
                    satelliteWindowStart[i] = point.tSec;
                }
                if (visible) {
                    satelliteLastVisible[i] = point.tSec;
                }
                if (!visible && satelliteInWindow[i]) {
                    satelliteWindows.add(new SatelliteVisibilityWindow(elements.name, satelliteWindowStart[i], satelliteLastVisible[i]));
                    satelliteInWindow[i] = false;
                }
            }

            if (anyVisible && !unionInWindow) {
                unionInWindow = true;
                unionWindowStart = point.tSec;
            }
            if (anyVisible) {
                unionLastVisible = point.tSec;
            }
            if (!anyVisible && unionInWindow) {
                unionWindows.add(new VisibilityWindow(unionWindowStart, unionLastVisible));
                unionInWindow = false;
            }
        }

        if (unionInWindow) {
            unionWindows.add(new VisibilityWindow(unionWindowStart, unionLastVisible));
        }
        for (int i = 0; i < satellites.size(); i++) {
            if (satelliteInWindow[i]) {
                satelliteWindows.add(new SatelliteVisibilityWindow(satellites.get(i).name, satelliteWindowStart[i], satelliteLastVisible[i]));
            }
        }

        long observableSeconds = 0L;
        for (VisibilityWindow window : unionWindows) {
            observableSeconds += Math.round(window.durationSec());
        }
        return new LaunchOffsetResult(offsetSecond, observableSeconds, unionWindows, satelliteWindows);
    }

    static LaunchEstimationResult estimateBestLaunchSecond(long startTimeSec, long stepSizeSec,
            long numCalculations, List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints,
            double sensorFovDeg, GeodeticDatum datum) {
        LaunchSearchResult searchResult = OrbitCalculator.searchLaunchOffsets(
                startTimeSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                datum
        );
        LaunchEstimationResult result = new LaunchEstimationResult();
        result.bestLaunchSecond = searchResult.best == null ? startTimeSec : searchResult.best.offsetSecond;
        result.bestObservableSeconds = searchResult.best == null ? 0L : searchResult.best.observableSeconds;
        result.candidateCount = searchResult.candidates.size();
        return result;
    }

    static List<VisibilityWindow> visibilityWindows(double launchOffsetSec, List<OrbitElements> satellites,
            List<BallisticPoint> oneSecondSamples, double sensorFovDeg, GeodeticDatum datum) {
        List<VisibilityWindow> windows = new ArrayList<VisibilityWindow>();
        SampledStateRuns<Boolean> runs = new SampledStateRuns<Boolean>(null, 0L);
        for (BallisticPoint point : oneSecondSamples) {
            boolean visible = isBallisticPointVisible(launchOffsetSec, point, satellites, sensorFovDeg, datum);
            SampledStateRuns.Segment<Boolean> completed = runs.accept(
                    visible ? Boolean.TRUE : null, Double.doubleToRawLongBits(point.tSec));
            if (completed != null) {
                windows.add(new VisibilityWindow(Double.longBitsToDouble(completed.getStart()),
                        Double.longBitsToDouble(completed.getLast())));
            }
        }
        if (runs.getState() != null) {
            windows.add(new VisibilityWindow(Double.longBitsToDouble(runs.getStart()),
                    Double.longBitsToDouble(runs.getLast())));
        }
        return windows;
    }

    static List<SatelliteVisibilityWindow> satelliteVisibilityWindows(double launchOffsetSec,
            List<OrbitElements> satellites, List<BallisticPoint> oneSecondSamples, double sensorFovDeg,
            GeodeticDatum datum) {
        List<SatelliteVisibilityWindow> windows = new ArrayList<SatelliteVisibilityWindow>();
        for (OrbitElements satellite : satellites) {
            SampledStateRuns<Boolean> runs = new SampledStateRuns<Boolean>(null, 0L);
            for (BallisticPoint point : oneSecondSamples) {
                boolean visible = isBallisticPointVisibleBySatellite(launchOffsetSec, point, satellite, sensorFovDeg, datum);
                SampledStateRuns.Segment<Boolean> completed = runs.accept(
                        visible ? Boolean.TRUE : null, Double.doubleToRawLongBits(point.tSec));
                if (completed != null) {
                    windows.add(new SatelliteVisibilityWindow(satellite.name,
                            Double.longBitsToDouble(completed.getStart()),
                            Double.longBitsToDouble(completed.getLast())));
                }
            }
            if (runs.getState() != null) {
                windows.add(new SatelliteVisibilityWindow(satellite.name,
                        Double.longBitsToDouble(runs.getStart()),
                        Double.longBitsToDouble(runs.getLast())));
            }
        }
        return windows;
    }

    static boolean isBallisticPointVisible(double launchOffsetSec, BallisticPoint point,
            List<OrbitElements> satellites, double sensorFovDeg, GeodeticDatum datum) {
        double absoluteTime = launchOffsetSec + point.tSec;
        Vector3 target = ballisticPointToEci(point, launchOffsetSec);
        for (OrbitElements elements : satellites) {
            Vector3 satellite = propagate(elements, absoluteTime);
            if (isTargetObservableFromSatellite(
                    satellite, target, sensorFovDeg, referenceEpochSec + absoluteTime)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBallisticPointVisibleBySatellite(double launchOffsetSec, BallisticPoint point,
            OrbitElements satelliteElements, double sensorFovDeg, GeodeticDatum datum) {
        double absoluteTime = launchOffsetSec + point.tSec;
        Vector3 target = ballisticPointToEci(point, launchOffsetSec);
        Vector3 satellite = propagate(satelliteElements, absoluteTime);
        return isTargetObservableFromSatellite(
                satellite, target, sensorFovDeg, referenceEpochSec + absoluteTime);
    }

    private static void validateSearchInputs(long numCalculations, long stepSizeSec,
            List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints, double sensorFovDeg) {
        if (numCalculations <= 0L) {
            throw new IllegalArgumentException("计算次数必须大于0。");
        }
        if (stepSizeSec <= 0L) {
            throw new IllegalArgumentException("计算步长必须大于0秒。");
        }
        if (sensorFovDeg <= 0.0 || sensorFovDeg > 180.0) {
            throw new IllegalArgumentException("传感器视场角应在0到180度之间。");
        }
        if (ballisticPoints == null || ballisticPoints.isEmpty()) {
            throw new IllegalArgumentException("弹道点不能为空。");
        }
        if (satellites == null || satellites.isEmpty()) {
            throw new IllegalArgumentException("请至少输入一颗卫星。");
        }
        for (OrbitElements elements : satellites) {
            validateOrbit(elements);
        }
    }

    static BallisticPoint interpolateAt(List<BallisticPoint> sorted, double t) {
        if (t <= sorted.get(0).tSec) {
            return copyWithTime(sorted.get(0), t);
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            BallisticPoint a = sorted.get(i);
            BallisticPoint b = sorted.get(i + 1);
            if (t <= b.tSec + 1e-9) {
                double span = b.tSec - a.tSec;
                double ratio = span <= 0.0 ? 0.0 : (t - a.tSec) / span;
                BallisticPoint point = new BallisticPoint();
                point.tSec = t;
                point.eciX = lerp(a.eciX, b.eciX, ratio);
                point.eciY = lerp(a.eciY, b.eciY, ratio);
                point.eciZ = lerp(a.eciZ, b.eciZ, ratio);
                point.eciVx = lerp(a.eciVx, b.eciVx, ratio);
                point.eciVy = lerp(a.eciVy, b.eciVy, ratio);
                point.eciVz = lerp(a.eciVz, b.eciVz, ratio);
                point.ecefX = lerp(a.ecefX, b.ecefX, ratio);
                point.ecefY = lerp(a.ecefY, b.ecefY, ratio);
                point.ecefZ = lerp(a.ecefZ, b.ecefZ, ratio);
                point.ecefVx = lerp(a.ecefVx, b.ecefVx, ratio);
                point.ecefVy = lerp(a.ecefVy, b.ecefVy, ratio);
                point.ecefVz = lerp(a.ecefVz, b.ecefVz, ratio);
                point.latDeg = lerp(a.latDeg, b.latDeg, ratio);
                point.lonDeg = lerp(a.lonDeg, b.lonDeg, ratio);
                point.heightM = lerp(a.heightM, b.heightM, ratio);
                point.speedMs = lerp(a.speedMs, b.speedMs, ratio);
                return point;
            }
        }
        return copyWithTime(sorted.get(sorted.size() - 1), t);
    }

    private static BallisticPoint copyWithTime(BallisticPoint source, double t) {
        BallisticPoint point = new BallisticPoint();
        point.tSec = t;
        point.eciX = source.eciX;
        point.eciY = source.eciY;
        point.eciZ = source.eciZ;
        point.eciVx = source.eciVx;
        point.eciVy = source.eciVy;
        point.eciVz = source.eciVz;
        point.ecefX = source.ecefX;
        point.ecefY = source.ecefY;
        point.ecefZ = source.ecefZ;
        point.ecefVx = source.ecefVx;
        point.ecefVy = source.ecefVy;
        point.ecefVz = source.ecefVz;
        point.latDeg = source.latDeg;
        point.lonDeg = source.lonDeg;
        point.heightM = source.heightM;
        point.speedMs = source.speedMs;
        return point;
    }

    static List<BallisticPoint> sortedBallistic(List<BallisticPoint> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("弹道点不能为空。");
        }
        List<BallisticPoint> sorted = new ArrayList<BallisticPoint>(points);
        Collections.sort(sorted, new Comparator<BallisticPoint>() {
            @Override
            public int compare(BallisticPoint a, BallisticPoint b) {
                return Double.compare(a.tSec, b.tSec);
            }
        });
        return sorted;
    }

    private static double lerp(double a, double b, double ratio) {
        return a + (b - a) * ratio;
    }
}
