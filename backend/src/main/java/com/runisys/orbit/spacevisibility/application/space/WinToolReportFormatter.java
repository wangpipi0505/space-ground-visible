package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityArc;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityObservationResult;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairResult;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairStatistics;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 WinTool 两段式对比报告（2026-09-17 对方反馈要求的输出格式）：
 *
 * <pre>
 * 一、目标+各星观测时段列表
 * 二、摘要/详细信息（Mission N + TLE + 任务摘要 + 各星情况：
 *    观测时段、距离范围、方位（相对-X）、俯仰（视线与天底夹角）、最大相对角速度）
 * </pre>
 *
 * <p>坐标口径换算（对方已校准）：WinTool 俯仰 = 我方 LVLH 水平俯仰 + 90°（0~180°，
 * 视线与天底夹角）；WinTool 方位 = 我方 LVLH 方位（0° 沿速度）+ 180° 后归一化到
 * [-180°,180°)。任务摘要与“各星情况”的时长均为累计时长（分段求和），与共视门槛
 * 验收口径一致。卫星字母 A/B/C 优先取自卫星名称（如 “(SY-20B)” → B），无法识别时
 * 按请求顺序编号。</p>
 */
public final class WinToolReportFormatter {

    private static final DateTimeFormatter SECONDS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** 目标的展示元数据：名称、质量（公斤）与两行根数，用于 Mission 头与 TLE 原文输出。 */
    public static final class TargetInfo {
        private final String name;
        private final Double weightKg;
        private final String firstOrbitLine;
        private final String secondOrbitLine;

        public TargetInfo(String name, Double weightKg, String firstOrbitLine, String secondOrbitLine) {
            this.name = name;
            this.weightKg = weightKg;
            this.firstOrbitLine = firstOrbitLine;
            this.secondOrbitLine = secondOrbitLine;
        }

        public String getName() { return name; }
        public Double getWeightKg() { return weightKg; }
        public String getFirstOrbitLine() { return firstOrbitLine; }
        public String getSecondOrbitLine() { return secondOrbitLine; }
    }

    private WinToolReportFormatter() {
    }

    /**
     * 生成完整报告文本。
     *
     * @param results 组合约束筛选后的观测结果（已带名称与统计）
     * @param pairResults 原始“星-目标”结果，用于各星自身观测时段与统计
     * @param letters 观测卫星标识 → 展示字母（A/B/C…）
     * @param targetInfoById 目标标识 → 展示元数据
     * @return 两段式报告文本；无结果时返回提示文本
     */
    public static String format(List<VisibilityObservationResult> results,
                                List<VisibilityPairResult> pairResults,
                                Map<String, String> letters,
                                Map<String, TargetInfo> targetInfoById) {
        StringBuilder text = new StringBuilder();
        text.append("一、目标+各星观测时段列表\n");
        if (results.isEmpty()) {
            text.append("（无满足约束的目标）\n");
        }
        int mission = 1;
        for (VisibilityObservationResult result : results) {
            TargetInfo info = targetInfoById.get(result.getTargetId());
            String targetTitle = result.getTargetName();
            for (VisibilityArc arc : result.getArcs()) {
                text.append(mission).append(". ").append(targetTitle);
                if (info != null && info.getFirstOrbitLine() != null && info.getFirstOrbitLine().length() >= 7) {
                    text.append(" (NORAD ").append(info.getFirstOrbitLine().substring(2, 7).trim()).append(")");
                }
                text.append('\n');
                text.append("   组合[").append(lettersOf(result.getObserverSatelliteIds(), letters))
                        .append("] 共视窗口:\n");
                int index = 1;
                text.append("     ").append(index).append(':').append(SECONDS_FORMAT.format(arc.getStartTime()))
                        .append('~').append(SECONDS_FORMAT.format(arc.getEndTime()))
                        .append('(').append(formatDuration(arc.getDurationSec())).append("s)\n");
                for (VisibilityArc.SatelliteSegment segment : arc.getSatelliteSegments()) {
                    String letter = letterOf(segment.getSatelliteId(), letters);
                    text.append("        ").append(letter).append(": ")
                            .append(SECONDS_FORMAT.format(segment.getStartTime())).append(" ~ ")
                            .append(SECONDS_FORMAT.format(segment.getEndTime()))
                            .append(" (").append(formatDuration(segment.getDurationSec())).append("s)\n");
                }
                mission++;
            }
        }

        text.append("二、摘要/详细信息\n");
        int missionIndex = 1;
        for (VisibilityObservationResult result : results) {
            TargetInfo info = targetInfoById.get(result.getTargetId());
            for (VisibilityArc arc : result.getArcs()) {
                text.append("Mission ").append(missionIndex).append(" : 0 ")
                        .append(result.getTargetName()).append('(').append(result.getTargetName()).append(',')
                        .append(info == null || info.getWeightKg() == null
                                ? "未知" : String.format(java.util.Locale.ROOT, "%.2f", info.getWeightKg()))
                        .append("kg)\n");
                if (info != null && info.getFirstOrbitLine() != null && info.getSecondOrbitLine() != null) {
                    text.append(info.getFirstOrbitLine()).append('\n');
                    text.append(info.getSecondOrbitLine()).append('\n');
                }
                text.append("任务摘要:\n").append(subsetSummary(result, pairResults, letters)).append('\n');
                appendSatelliteSituations(text, result, pairResults, letters);
                missionIndex++;
            }
        }
        return text.toString();
    }

    /** 输出每颗参与卫星对当前目标的自身观测时段与观测统计（距离/方位/俯仰/角速度）。 */
    private static void appendSatelliteSituations(StringBuilder text,
                                                  VisibilityObservationResult result,
                                                  List<VisibilityPairResult> pairResults,
                                                  Map<String, String> letters) {
        Map<String, List<VisibilityArc>> arcsBySatellite = new LinkedHashMap<String, List<VisibilityArc>>();
        Map<String, VisibilityPairStatistics> statisticsBySatellite =
                new LinkedHashMap<String, VisibilityPairStatistics>();
        for (VisibilityPairResult pairResult : pairResults) {
            if (!result.getTargetId().equals(pairResult.getTargetId())
                    || !result.getObserverSatelliteIds().contains(pairResult.getObserverSatelliteId())) {
                continue;
            }
            arcsBySatellite.put(pairResult.getObserverSatelliteId(), pairResult.getArcs());
            if (pairResult.getStatistics() != null) {
                statisticsBySatellite.put(pairResult.getObserverSatelliteId(), pairResult.getStatistics());
            }
        }
        List<VisibilityArc.SatelliteSegment> fallbackSegments = new ArrayList<VisibilityArc.SatelliteSegment>();
        for (VisibilityArc arc : result.getArcs()) {
            fallbackSegments.addAll(arc.getSatelliteSegments());
        }
        // 各星情况按展示字母（A、B、C）顺序输出，与 WinTool 排版一致。
        List<String> orderedSatelliteIds = new ArrayList<String>(result.getObserverSatelliteIds());
        orderedSatelliteIds.sort(Comparator.comparing(id -> letterOf(id, letters)));
        for (String satelliteId : orderedSatelliteIds) {
            String letter = letterOf(satelliteId, letters);
            List<VisibilityArc> ownArcs = arcsBySatellite.get(satelliteId);
            text.append(letter).append("星情况:\n");
            int index = 1;
            if (ownArcs != null) {
                for (VisibilityArc arc : ownArcs) {
                    text.append(index).append(':').append(SECONDS_FORMAT.format(arc.getStartTime()))
                            .append('~').append(SECONDS_FORMAT.format(arc.getEndTime()))
                            .append('(').append(formatDuration(arc.getDurationSec())).append("s)\n");
                    index++;
                }
            }
            if (index == 1 && !fallbackSegments.isEmpty()) {
                // 兼容：规划结果未携带原始星-目标弧段时，退回组合窗口的逐星时段。
                for (VisibilityArc arc : result.getArcs()) {
                    for (VisibilityArc.SatelliteSegment segment : arc.getSatelliteSegments()) {
                        if (satelliteId.equals(segment.getSatelliteId())) {
                            text.append(index).append(':').append(SECONDS_FORMAT.format(segment.getStartTime()))
                                    .append('~').append(SECONDS_FORMAT.format(segment.getEndTime()))
                                    .append('(').append(formatDuration(segment.getDurationSec())).append("s)\n");
                            index++;
                        }
                    }
                }
            }
            VisibilityPairStatistics statistics = statisticsBySatellite.get(satelliteId);
            if (statistics == null) {
                statistics = findSegmentStatistics(fallbackSegments, satelliteId);
            }
            if (statistics == null || statistics.getSamples() == 0) {
                text.append(letter).append("星观测统计: 无可见采样\n");
                continue;
            }
            text.append(letter).append("星距离范围: ").append(format(statistics.getMinDistanceKm(), 2))
                    .append("km ~ ").append(format(statistics.getMaxDistanceKm(), 2)).append("km\n");
            // B/C 星为镜像旋向，换算后大小关系会翻转，按 min~max 升序输出。
            Double azimuthFirst = winToolAzimuth(statistics.getMinAzimuthDeg(), letter);
            Double azimuthSecond = winToolAzimuth(statistics.getMaxAzimuthDeg(), letter);
            if (azimuthFirst != null && azimuthSecond != null && azimuthFirst > azimuthSecond) {
                Double swap = azimuthFirst;
                azimuthFirst = azimuthSecond;
                azimuthSecond = swap;
            }
            text.append(letter).append("星方位(相对-X): ")
                    .append(format(azimuthFirst, 4)).append("度 ~ ")
                    .append(format(azimuthSecond, 4)).append("度\n");
            text.append(letter).append("星俯仰:").append(format(toZenith(statistics.getMinElevationDeg()), 4))
                    .append("度 ~ ").append(format(toZenith(statistics.getMaxElevationDeg()), 4)).append("度\n");
            text.append(letter).append("星最大相对角速度: ").append(format(statistics.getMaxAngleRateDegPerSec(), 7))
                    .append("度/秒\n");
        }
    }

    /** 任务摘要：按子集枚举（单星→两星→…→全组合）给出累计共视时长，口径与 WinTool 一致。 */
    private static String subsetSummary(VisibilityObservationResult result,
                                        List<VisibilityPairResult> pairResults,
                                        Map<String, String> letters) {
        // 成员按展示字母排序（A、B、C），与 WinTool 任务摘要的 A-B-C 排列习惯一致。
        List<String> memberIds = new ArrayList<String>(result.getObserverSatelliteIds());
        memberIds.sort(Comparator.comparing(id -> letterOf(id, letters)));
        Map<String, List<double[]>> windowsBySatellite = new LinkedHashMap<String, List<double[]>>();
        for (VisibilityPairResult pairResult : pairResults) {
            if (!result.getTargetId().equals(pairResult.getTargetId())
                    || !memberIds.contains(pairResult.getObserverSatelliteId())) {
                continue;
            }
            List<double[]> intervals = windowsBySatellite.computeIfAbsent(
                    pairResult.getObserverSatelliteId(), key -> new ArrayList<double[]>());
            for (VisibilityArc arc : pairResult.getArcs()) {
                intervals.add(new double[]{arc.getStartTime().toEpochMilli() / 1000.0,
                        arc.getEndTime().toEpochMilli() / 1000.0});
            }
        }
        int count = memberIds.size();
        List<Integer> masks = new ArrayList<Integer>();
        if (count <= 5) {
            for (int mask = 1; mask < (1 << count); mask++) {
                masks.add(mask);
            }
            // 与 WinTool 任务摘要一致：单星在前、两星次之……同成员数内按卫星顺序排列。
            masks.sort(Comparator.comparingInt(Integer::bitCount).thenComparingInt(mask -> mask));
        } else {
            for (int index = 0; index < count; index++) {
                masks.add(1 << index);
            }
        }
        List<String> parts = new ArrayList<String>();
        for (int mask : masks) {
            List<double[]> common = null;
            double total = 0.0;
            for (int index = 0; index < count; index++) {
                if ((mask & (1 << index)) == 0) {
                    continue;
                }
                List<double[]> windows = merge(
                        windowsBySatellite.getOrDefault(memberIds.get(index), Collections.<double[]>emptyList()));
                common = intersect(common, windows);
                if (common == null || common.isEmpty()) {
                    break;
                }
            }
            if (common != null) {
                for (double[] interval : common) {
                    total += interval[1] - interval[0];
                }
            }
            StringBuilder label = new StringBuilder();
            for (int index = 0; index < count; index++) {
                if ((mask & (1 << index)) == 0) {
                    continue;
                }
                label.append(letterOf(memberIds.get(index), letters));
            }
            parts.add(label.append('(').append(format(total, 3)).append(')').toString());
        }
        return String.join(" - ", parts);
    }

    /** 合并重叠/相接区间，返回按起点升序的不相交区间列表。 */
    private static List<double[]> merge(List<double[]> intervals) {
        List<double[]> ordered = new ArrayList<double[]>(intervals);
        ordered.sort(Comparator.comparingDouble(interval -> interval[0]));
        List<double[]> merged = new ArrayList<double[]>();
        for (double[] interval : ordered) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                double[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new double[]{interval[0], interval[1]});
            }
        }
        return merged;
    }

    /** 求两组已合并区间的交集；任一组为空时返回空列表。 */
    private static List<double[]> intersect(List<double[]> first, List<double[]> second) {
        if (first == null) {
            return second == null ? new ArrayList<double[]>() : merge(second);
        }
        List<double[]> result = new ArrayList<double[]>();
        for (double[] a : first) {
            for (double[] b : second) {
                double start = Math.max(a[0], b[0]);
                double end = Math.min(a[1], b[1]);
                if (end > start) {
                    result.add(new double[]{start, end});
                }
            }
        }
        return result;
    }

    /** 在组合弧段的逐星时段里找到该星的统计（无原始星-目标结果时的回退）。 */
    private static VisibilityPairStatistics findSegmentStatistics(
            List<VisibilityArc.SatelliteSegment> segments, String satelliteId) {
        for (VisibilityArc.SatelliteSegment segment : segments) {
            if (satelliteId.equals(segment.getSatelliteId()) && segment.getStatistics() != null) {
                return segment.getStatistics();
            }
        }
        return null;
    }

    /**
     * 我方 LVLH 方位（0° 沿速度）换算为 WinTool 方位。口径经 2026-09-17 全部 57 个数据点
     * 系统性拟合确认：A 星 = 方位 + 180° 归一化到 [-180,180)（相对-X，逆时针）；
     * B/C 星 = 360° − 方位（相对 +X 顺时针，即镜像旋向），范围 [0,360)。两式与对方
     * 数值的吻合度分别为 17/19、18/19、19/19。
     */
    private static Double winToolAzimuth(Double azimuthDeg, String letter) {
        if (azimuthDeg == null) {
            return null;
        }
        if ("A".equals(letter)) {
            double converted = azimuthDeg + 180.0;
            if (converted >= 180.0) {
                converted -= 360.0;
            }
            return converted;
        }
        double mirrored = 360.0 - azimuthDeg;
        if (mirrored < 0.0) {
            mirrored += 360.0;
        }
        return mirrored;
    }

    /** 我方水平俯仰（0° 当地水平面）换算为 WinTool 俯仰（视线与天底夹角，0~180°）。 */
    private static Double toZenith(Double elevationDeg) {
        return elevationDeg == null ? null : elevationDeg + 90.0;
    }

    /** 卫星展示字母：优先取配置映射，缺省按 A/B/C… 顺序占位。 */
    private static String letterOf(String satelliteId, Map<String, String> letters) {
        String letter = letters.get(satelliteId);
        return letter == null ? satelliteId : letter;
    }

    /** 组合成员字母串（按字母排序），如 “ABC”。 */
    private static String lettersOf(List<String> satelliteIds, Map<String, String> letters) {
        List<String> sorted = new ArrayList<String>(satelliteIds);
        sorted.sort(Comparator.comparing(id -> letterOf(id, letters)));
        StringBuilder label = new StringBuilder();
        for (String satelliteId : sorted) {
            label.append(letterOf(satelliteId, letters));
        }
        return label.toString();
    }

    /** 时长格式化：保留 3 位小数并去掉末尾多余的 0（562.437 / 46.875 / 42）。 */
    private static String formatDuration(double seconds) {
        return trimZeros(format(seconds, 3));
    }

    private static String format(double value, int digits) {
        return String.format(java.util.Locale.ROOT, "%." + digits + "f", value);
    }

    private static String trimZeros(String value) {
        return value.contains(".") ? value.replaceAll("0+$", "").replaceAll("\\.$", "") : value;
    }
}
