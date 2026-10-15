package com.runisys.orbit.spacevisibility.domain.window;

import com.runisys.orbit.spacevisibility.domain.space.visibility.CombinationConstraint;
import com.runisys.orbit.spacevisibility.domain.space.visibility.CombinationWindow;
import com.runisys.orbit.spacevisibility.domain.space.visibility.CombinationWindowCalculator;

import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator.*;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

/**
 * 仅调用原有入口的独立样例驱动，编译时既可绑定冻结原classes，也可绑定迁入实现。
 * 不复制区间、累计、扫描或best算法；浮点结果保存rawbits，采样调用顺序和统计一起保存。
 */
public final class WindowReferenceScenarios {
    private WindowReferenceScenarios() { }

    /** 返回可由Jackson序列化的确定性数据；没有文件路径、当前时间或运行环境字段。 */
    public static Map<String, Object> capture() throws Exception {
        double reference = OrbitCalculator.referenceEpochSec;
        double offset = OrbitCalculator.utcOffsetSec;
        try {
            OrbitCalculator.utcOffsetSec = 0.0;
            return captureAtUtc();
        } finally {
            // 同一JVM中的其它原回归测试可以设置旧静态字段，样例不能污染它们的运行环境。
            OrbitCalculator.referenceEpochSec = reference;
            OrbitCalculator.utcOffsetSec = offset;
        }
    }

    private static Map<String, Object> captureAtUtc() throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Object> intervals = new ArrayList<Object>();
        double[][] pairs = {{0, 0}, {0, -0.0}, {-0.0, 0}, {1, 1.001}, {5, 4},
                {Double.NaN, 1}, {0, Double.POSITIVE_INFINITY}, {-Double.MAX_VALUE, Double.MAX_VALUE}};
        for (double[] p : pairs) {
            List<Object> row = new ArrayList<Object>(Arrays.asList(bits(p[0]), bits(p[1])));
            try { row.add(bits(TimeInterval.of(p[0], p[1]).durationSec())); }
            catch (RuntimeException e) { row.add(e.getClass().getName() + ":" + e.getMessage()); }
            row.add(bits(new VisibilityWindow(p[0], p[1]).durationSec()));
            row.add(bits(new SatelliteVisibilityWindow("s", p[0], p[1]).durationSec()));
            intervals.add(row);
        }
        result.put("durationPolicies", intervals);
        List<TimeInterval> left = Arrays.asList(TimeInterval.of(4, 6), TimeInterval.of(0, 2), TimeInterval.of(9, 10));
        List<TimeInterval> right = Arrays.asList(TimeInterval.of(2, 4), TimeInterval.of(1, 5), TimeInterval.of(20, 21));
        List<Object> intersections = new ArrayList<Object>();
        for (TimeInterval a : left) for (TimeInterval b : right) intersections.add(interval(a.intersection(b)));
        result.put("closedIntersections", intersections);
        Map<String, List<TimeInterval>> bySatellite = new LinkedHashMap<String, List<TimeInterval>>();
        bySatellite.put("A", left); bySatellite.put("B", right);
        for (double min : new double[]{0, 2, 2.001}) {
            List<Object> windows = new ArrayList<Object>();
            for (CombinationWindow w : CombinationWindowCalculator.calculate(bySatellite,
                    new CombinationConstraint(Arrays.asList("A", "B"), min))) windows.add(interval(w.getInterval()));
            result.put("combination" + min, windows);
        }
        List<TimeInterval> fragments = Arrays.asList(TimeInterval.of(4, 6), TimeInterval.of(0, 2),
                TimeInterval.of(2.25, 3), TimeInterval.of(6, 6));
        for (double gap : new double[]{0, .25, 1, Double.NaN}) {
            result.put("normalized" + gap, intervalList(WindowNormalizer.normalize(fragments, .1, .2, gap)));
        }
        try { WindowNormalizer.normalize(null, -1, 0, 0); }
        catch (RuntimeException e) { result.put("invalidGuard", e.getMessage()); }
        Instant origin = Instant.parse("2026-07-01T10:00:00.123456789Z");
        for (int scenario = 0; scenario < 7; scenario++) {
            final int mode = scenario;
            List<Long> calls = new ArrayList<Long>();
            List<Long> stats = new ArrayList<Long>();
            List<StateInterval<String>> states = TimeWindowScanner.scanStates(origin, origin.plusSeconds(8),
                    4, .25, .5, t -> {
                        long n = java.time.Duration.between(origin, t).toNanos(); calls.add(n);
                        return state(mode, n);
                    }, s -> { stats.add(s.getStateEvaluations()); stats.add(s.getAdaptiveProbeEvaluations()); stats.add(s.getRefinedRangeCount()); });
            List<Object> rows = new ArrayList<Object>();
            for (StateInterval<String> s : states) rows.add(Arrays.asList(s.getState(), interval(s.getInterval())));
            result.put("states" + scenario, Arrays.asList(rows, calls, stats));
            result.put("visible" + scenario, intervalList(TimeWindowScanner.scan(origin, origin.plusSeconds(8),
                    4, .25, .5, t -> state(mode, java.time.Duration.between(origin, t).toNanos()) != null)));
        }
        // 长到double秒无法表达每一纳秒的区间仅粗扫一次，检查内部Long路径和尾边界。
        result.put("longNanos", intervalList(TimeWindowScanner.scan(origin, origin.plusNanos(9007199254740993L),
                10000000, 10000000, t -> true)));
        Method stable = Class.forName("com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticArcSupport").getDeclaredMethod("stableVisibleArcs", List.class, int.class, double.class);
        stable.setAccessible(true);
        List<SatelliteVisibilityResult> arcs = Arrays.asList(null, arc(1, 0, 0, 1), arc(2, 0, .000999999, 2),
                arc(3, 0, .001, 2), arc(4, 10, 12, 3), arc(5, Double.NaN, Double.NaN, 3), arc(6, 0, -0.0, 2));
        for (int points : new int[]{-1, 2, 4}) for (double duration : new double[]{-.1, .001, Double.NaN}) {
            List<Integer> ids = new ArrayList<Integer>();
            for (Object obj : (List<?>) stable.invoke(null, arcs, points, duration)) ids.add(((SatelliteVisibilityResult) obj).arcIndex);
            result.put("stable" + points + ":" + duration, ids);
        }
        result.put("stableNull", ((List<?>) stable.invoke(null, null, 2, .001)).size());
        for (List<SatelliteVisibilityResult> values : Arrays.asList(arcs,
                Arrays.asList(arc(1, 0, 0, 4)), Arrays.asList(arc(1, 0, 10000000000000000.0, 2), arc(2, 0, 1, 2), arc(3, 0, 1, 2)),
                Arrays.asList(arc(2, 0, 1, 2), arc(3, 0, 1, 2), arc(1, 0, 10000000000000000.0, 2)))) {
            LaunchOffsetCalcResult calc = new LaunchOffsetCalcResult(); calc.visibleSatellites.addAll(values);
            result.put("total" + result.size(), Arrays.asList(bits(calc.totalObservableDurationSec()), calc.totalObservableSeconds(), calc.totalObservablePointCount()));
        }
        // 真正原传播/采样入口：两载荷重叠、首尾可见、间断两段、单点、全部不可见，输出开关。
        double epoch = OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00.000");
        OrbitCalculator.referenceEpochSec = epoch;
        for (int mode = 0; mode < 4; mode++) for (int output = 0; output <= 1; output++) {
            OrbitElements sat = satellite(epoch);
            for (String id : Arrays.asList("P1", "P2")) {
                SatellitePayloadConfig payload = new SatellitePayloadConfig(); payload.payloadId = id; payload.payloadName = id;
                payload.sensorConfig = SensorVisibilityConfig.forPayload("对地监视", 0, 0); sat.payloads.add(payload);
            }
            List<BallisticPoint> samples = new ArrayList<BallisticPoint>();
            for (int i = 0; i < 7; i++) {
                Vector3 position = OrbitCalculator.propagateAtAbsolute(sat, epoch + i, OrbitModel.TWO_BODY);
                boolean visible = mode == 0 || (mode == 1 && i != 3) || (mode == 2 && i == 3);
                Vector3 target = position.scale((visible ? 6800000.0 : -6800000.0) / position.norm());
                Vector3 ecef = OrbitCalculator.eciToEcefPos(target, epoch + i);
                BallisticPoint p = new BallisticPoint(); p.tSec = i; p.ecefX = ecef.x; p.ecefY = ecef.y; p.ecefZ = ecef.z; samples.add(p);
            }
            LaunchSearchResultV2 search = OrbitCalculator.searchLaunchOffsetsV2(epoch, 0, 1, 3,
                    Arrays.asList(sat), samples, 140, OrbitModel.TWO_BODY, 1, epoch, output,
                    SensorVisibilityConfig.forPayload("对地监视", 0, 0), null);
            // 完整层级JSON也冻结；不重建摘要、点数或best期望。
            result.put("ballistic" + mode + ":" + output, search.toJson());
            List<Object> old = new ArrayList<Object>();
            for (VisibilityWindow w : OrbitCalculator.visibilityWindows(0, Arrays.asList(sat), samples, 140, GeodeticDatum.WGS84))
                old.add(Arrays.asList(bits(w.startRelSec), bits(w.endRelSec), bits(w.durationSec())));
            result.put("oldWindows" + mode + ":" + output, old);
            List<Object> perSatellite = new ArrayList<Object>();
            for (SatelliteVisibilityWindow w : OrbitCalculator.satelliteVisibilityWindows(
                    0, Arrays.asList(sat), samples, 140, GeodeticDatum.WGS84)) {
                perSatellite.add(Arrays.asList(w.satelliteName, bits(w.startRelSec), bits(w.endRelSec), bits(w.durationSec())));
            }
            result.put("oldSatelliteWindows" + mode + ":" + output, perSatellite);
        }
        Method hysteresis = Class.forName("com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticVisibilityGeometry").getDeclaredMethod("isTargetObservableFromSatelliteStable",
                Vector3.class, Vector3.class, SensorVisibilityConfig.class, double.class, boolean.class);
        hysteresis.setAccessible(true);
        List<Object> boundary = new ArrayList<Object>();
        Vector3 satellite = new Vector3(7000000, 0, 0);
        for (double delta : new double[]{-1e-8, 0, .5e-9, 2e-9, .5e-6, 2e-6}) {
            double radians = Math.toRadians(57.5 + delta);
            Vector3 target = satellite.add(new Vector3(-Math.cos(radians), Math.sin(radians), 0).scale(100000));
            boundary.add(Arrays.asList(bits(delta), hysteresis.invoke(null, satellite, target,
                    SensorVisibilityConfig.forPayload("对地监视", 0, 0), epoch, false),
                    hysteresis.invoke(null, satellite, target, SensorVisibilityConfig.forPayload("对地监视", 0, 0), epoch, true)));
        }
        result.put("hysteresis", boundary);
        SatelliteVisibilityResult fallback = arc(1, Double.NaN, Double.NaN, 0);
        fallback.trajectoryPoints.add(new VisibleTrajectoryPoint(0, 0, 0, epoch, -.0));
        fallback.trajectoryPoints.add(new VisibleTrajectoryPoint(0, 0, 0, epoch + .25, .25));
        LaunchOffsetCalcResult fromPoints = new LaunchOffsetCalcResult(); fromPoints.visibleSatellites.add(fallback);
        result.put("pointListFallback", Arrays.asList(bits(fromPoints.totalObservableDurationSec()), fromPoints.totalObservablePointCount()));
        List<String> invalid = new ArrayList<String>();
        for (double[] steps : new double[][]{{0, 1, 1}, {1, 2, 2}, {1, 1, .5}, {Double.NaN, 1, 1}, {1000000, .000001, .000001}}) {
            try { TimeWindowScanner.scan(origin, origin.plusSeconds(1), steps[0], steps[1], steps[2], t -> true); invalid.add("accepted"); }
            catch (RuntimeException e) { invalid.add(e.getClass().getName() + ":" + e.getMessage()); }
        }
        try { TimeWindowScanner.scan(origin, origin.plusSeconds(10000000000L), 1, 1, t -> true); }
        catch (RuntimeException e) { invalid.add(e.getClass().getName() + ":" + e.getMessage()); }
        result.put("scannerInvalid", invalid);
        return result;
    }

    private static String state(int mode, long n) {
        double s = n / 1e9;
        if (mode == 0) return null;
        if (mode == 1) return "A";
        if (mode == 2) return s >= 2 && s < 3 ? "A" : null;
        if (mode == 3) return s >= 2 && s < 3 ? "B" : "A";
        if (mode == 4) return s < 1 || s >= 6 ? "A" : null;
        if (mode == 5) return s == 2 ? "A" : null;
        return s >= 2 && s < 2.5 ? "A" : null;
    }
    private static SatelliteVisibilityResult arc(int index, double start, double end, int count) {
        SatelliteVisibilityResult arc = new SatelliteVisibilityResult(); arc.arcIndex = index;
        arc.visiblePointCount = count; arc.startRelativeTimeSec = start; arc.endRelativeTimeSec = end;
        return arc;
    }
    private static OrbitElements satellite(double epoch) {
        OrbitElements sat = new OrbitElements(); sat.name = "SAT-A"; sat.satelliteId = "SAT-A";
        sat.semiMajorAxisM = 7530612; sat.eccentricity = .00085; sat.inclinationDeg = 64.8;
        sat.raanDeg = 120; sat.argumentOfPerigeeDeg = 270; sat.meanAnomalyDeg = 30; sat.epochSec = epoch;
        return sat;
    }
    private static String bits(double value) { return Long.toHexString(Double.doubleToRawLongBits(value)); }
    private static Object interval(TimeInterval value) {
        return value == null ? null : Arrays.asList(bits(value.getStartSec()), bits(value.getEndSec()), bits(value.durationSec()));
    }
    private static List<Object> intervalList(List<TimeInterval> values) {
        List<Object> out = new ArrayList<Object>(); for (TimeInterval value : values) out.add(interval(value)); return out;
    }
}
