package com.runisys.orbit.spacevisibility.domain.ballistic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.finalVisibleWindow;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.finalVisibleWindowFromWindows;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.firstVisibilityConstraint;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.sourceReferenceFor;
import static com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator.*;

/**
 * 弹道结果的JSON及中文文本输出实现，保留既有字段、分组、数字与空值格式。
 * 字符串、HTTP流和文件输出共用同一份JSON主体；此类不参与窗口或轨迹数值计算。
 */
final class BallisticResultWriter {
    private BallisticResultWriter() {
    }

    static String toJson(LaunchOffsetCalcResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("  {\n");
        sb.append("    \"offsetSecond\": ").append(result.offsetSecond).append(",\n");
        sb.append("    \"launchTimeUtc\": \"").append(jsonEscape(result.launchTimeUtc)).append("\",\n");
        sb.append("    \"launchTimeJ2000Sec\": ").append(formatJsonNumber(result.launchTimeJ2000Sec)).append(",\n");
        sb.append("    \"payloadType\": \"").append(jsonEscape(outputPayloadType(result))).append("\",\n");
        sb.append("    \"visibilityConstraint\": ");
        appendVisibilityConstraintJson(sb, outputVisibilityConstraint(result));
        sb.append(",\n");
        sb.append("    \"finalVisibleWindow\": ");
        appendTimeWindowJson(sb, finalVisibleWindow(result.visibleSatellites));
        sb.append(",\n");
        sb.append("    \"visibleArcCount\": ").append(result.visibleSatellites.size()).append(",\n");
        sb.append("    \"visiblePointCount\": ").append(result.totalObservablePointCount()).append(",\n");
        sb.append("    \"visibleDurationSec\": ").append(formatJsonNumber(result.totalObservableDurationSec())).append(",\n");
        sb.append("    \"targets\": [\n");
        List<TargetVisibilitySummary> outputTargets = outputTargets(result);
        for (int ti = 0; ti < outputTargets.size(); ti++) {
            if (ti > 0) sb.append(",\n");
            appendTargetJson(sb, outputTargets.get(ti), "      ");
        }
        sb.append("\n    ],\n");
        sb.append("    \"visibleSatellites\": [\n");
        List<List<SatelliteVisibilityResult>> satelliteGroups = satelliteArcGroups(result);
        for (int si = 0; si < satelliteGroups.size(); si++) {
            if (si > 0) sb.append(",\n");
            List<SatelliteVisibilityResult> group = satelliteGroups.get(si);
            SatelliteVisibilityResult firstArc = group.get(0);
            sb.append("      {\n");
            sb.append("        \"satelliteId\": \"").append(jsonEscape(firstArc.satelliteId)).append("\",\n");
            sb.append("        \"satelliteName\": \"").append(jsonEscape(firstArc.satelliteName)).append("\",\n");
            sb.append("        \"satelliteIndex\": ").append(firstArc.satelliteIndex).append(",\n");
            sb.append("        \"finalVisibleWindow\": ");
            appendTimeWindowJson(sb, BallisticArcSupport.finalVisibleWindow(group));
            sb.append(",\n");
            sb.append("        \"payloadResults\": [\n");
            List<List<SatelliteVisibilityResult>> payloadGroups = satellitePayloadArcGroups(group);
            for (int pi = 0; pi < payloadGroups.size(); pi++) {
                if (pi > 0) sb.append(",\n");
                appendSatellitePayloadJson(sb, payloadGroups.get(pi), "          ");
            }
            sb.append("\n        ]\n");
            sb.append("      }");
        }
        sb.append("\n    ]\n");
        sb.append("  }");
        return sb.toString();
    }

    private static String outputPayloadType(LaunchOffsetCalcResult result) {
        if (!isBlank(result.payloadType)) {
            return result.payloadType;
        }
        if (result.visibilityConstraint != null && !isBlank(result.visibilityConstraint.payloadType)) {
            return result.visibilityConstraint.payloadType;
        }
        return PayloadType.GROUND_SURVEILLANCE.displayName();
    }

    private static VisibilityConstraintSummary outputVisibilityConstraint(LaunchOffsetCalcResult result) {
        if (result.visibilityConstraint != null) {
            return result.visibilityConstraint;
        }
        return VisibilityConstraintSummary.fromConfig(SensorVisibilityConfig.forPayload(outputPayloadType(result), 0.0,
                DEFAULT_SUN_EXCLUSION_ANGLE_DEG));
    }

    private static List<TargetVisibilitySummary> outputTargets(LaunchOffsetCalcResult result) {
        if (!result.targets.isEmpty()) {
            return result.targets;
        }
        List<TargetVisibilitySummary> summaries = new ArrayList<TargetVisibilitySummary>();
        summaries.add(TargetVisibilitySummary.fromCalc(result));
        return summaries;
    }

    private static List<List<SatelliteVisibilityResult>> satelliteArcGroups(LaunchOffsetCalcResult result) {
        return groupArcs(result.visibleSatellites, arc -> arc.satelliteIndex + "\u0001" + nullToEmpty(arc.satelliteId)
                + "\u0001" + nullToEmpty(arc.satelliteName));
    }

    private static List<List<SatelliteVisibilityResult>> satellitePayloadArcGroups(
            List<SatelliteVisibilityResult> arcs) {
        return groupArcs(arcs, arc -> nullToEmpty(arc.payloadId) + "\u0001"
                + nullToEmpty(arc.payloadName) + "\u0001" + nullToEmpty(arc.payloadType));
    }

    /** 字符串与HTTP/文件输出共用同一序列化主体，避免两份字段逻辑发生漂移。 */
    static String toJson(LaunchSearchResultV2 result, boolean includeBest) {
        java.io.StringWriter writer = new java.io.StringWriter();
        try {
            writeJsonTo(result, writer, includeBest);
        } catch (IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
        return writer.toString();
    }

    static void writeJsonTo(LaunchSearchResultV2 result, java.io.Writer writer, boolean includeBest) throws IOException {
        writer.write("{\n");
        writer.write("  \"referenceUtc\": \"");
        writer.write(jsonEscape(result.referenceUtc));
        writer.write("\",\n");
        writer.write("  \"payloadType\": \"");
        writer.write(jsonEscape(outputPayloadType(result)));
        writer.write("\",\n");
        writer.write("  \"visibilityConstraint\": ");
        StringBuilder constraint = new StringBuilder();
        appendVisibilityConstraintJson(constraint, outputVisibilityConstraint(result));
        writer.write(constraint.toString());
        writer.write(",\n");
        if (includeBest) {
            if (result.best == null) {
                writer.write("  \"best\": null,\n");
            } else {
                writer.write("  \"best\":\n");
                writer.write(result.best.toJson());
                writer.write(",\n");
            }
        }
        writer.write("  \"allResults\": [\n");
        for (int ci = 0; ci < result.allResults.size(); ci++) {
            if (ci > 0) {
                writer.write(",\n");
            }
            writer.write(result.allResults.get(ci).toJson());
        }
        writer.write("\n  ]\n");
        writer.write("}\n");
    }

    private static String outputPayloadType(LaunchSearchResultV2 result) {
        VisibilityConstraintSummary summary = outputVisibilityConstraint(result);
        return summary == null || isBlank(summary.payloadType)
                ? PayloadType.GROUND_SURVEILLANCE.displayName()
                : summary.payloadType;
    }

    private static VisibilityConstraintSummary outputVisibilityConstraint(LaunchSearchResultV2 result) {
        if (result.visibilityConstraint != null) {
            return result.visibilityConstraint;
        }
        return firstVisibilityConstraint(result.allResults, result.best);
    }

    static String toChineseText(LaunchSearchResultV2 result) {
        if (result.best == null) return "未找到有效结果。\n";
        StringBuilder sb = new StringBuilder();
        sb.append("候选偏移量数量：").append(result.allResults.size()).append('\n');
        sb.append("第1目标CSV参考时间：").append(result.referenceUtc).append('\n');
        sb.append("最优偏移量：").append(signedSeconds(result.best.offsetSecond)).append('\n');
        if (result.best.targets.isEmpty()) {
            sb.append("第1目标调整后发射时间：").append(result.best.launchTimeUtc).append('\n');
        } else {
            sb.append("各目标基准发射时间与调整后发射时间：\n");
            for (TargetVisibilitySummary target : result.best.targets) {
                sb.append("  - ")
                        .append(target.targetId == null || target.targetId.trim().isEmpty()
                                ? "UNKNOWN" : target.targetId)
                        .append("：基准发射UTC=")
                        .append(target.baseLaunchUTC == null || target.baseLaunchUTC.trim().isEmpty()
                                ? "未填写，使用CSV参考时间" : target.baseLaunchUTC)
                        .append("，调整后发射UTC=")
                        .append(target.launchUTC == null ? "" : target.launchUTC)
                        .append('\n');
            }
        }
        sb.append("最大可见导弹时长(逐星累计)：")
                .append(String.format(Locale.CHINA, "%.3f", result.best.totalObservableDurationSec()))
                .append(" s\n");
        sb.append("可见轨迹点数量：").append(result.best.totalObservablePointCount()).append('\n');
        sb.append("可见弧段数量：").append(result.best.visibleSatellites.size()).append('\n');
        return sb.toString();
    }

    private static void appendVisibilityConstraintJson(StringBuilder sb, VisibilityConstraintSummary summary) {
        VisibilityConstraintSummary active = summary == null
                ? VisibilityConstraintSummary.fromConfig(SensorVisibilityConfig.forPayload(PayloadType.GROUND_SURVEILLANCE))
                : summary;
        sb.append("{");
        sb.append("\"payloadType\": \"").append(jsonEscape(active.payloadType)).append("\", ");
        sb.append("\"maxDetectRangeKm\": ").append(formatJsonNumber(active.maxDetectRangeKm)).append(", ");
        sb.append("\"sunExclusionAngleDeg\": ").append(formatJsonNumber(active.sunExclusionAngleDeg)).append(", ");
        sb.append("\"simpleConeHalfAngleDeg\": ").append(formatJsonNumber(active.simpleConeHalfAngleDeg)).append(", ");
        sb.append("\"halfConeAngleDeg\": ").append(formatJsonNumber(active.halfConeAngleDeg)).append(", ");
        sb.append("\"innerConeAngleDeg\": ").append(formatJsonNumber(active.innerConeAngleDeg)).append(", ");
        sb.append("\"outerConeAngleDeg\": ").append(formatJsonNumber(active.outerConeAngleDeg));
        sb.append("}");
    }

    private static void appendTimeWindowJson(StringBuilder sb, TimeWindowSummary window) {
        if (window == null) {
            sb.append("null");
            return;
        }
        sb.append("{");
        sb.append("\"startTime\": \"").append(jsonEscape(window.startUtc)).append("\", ");
        sb.append("\"endTime\": \"").append(jsonEscape(window.endUtc)).append("\", ");
        sb.append("\"startJ2000Sec\": ").append(formatJsonNumber(window.startJ2000Sec)).append(", ");
        sb.append("\"endJ2000Sec\": ").append(formatJsonNumber(window.endJ2000Sec)).append(", ");
        sb.append("\"durationSeconds\": ").append(formatJsonNumber(window.durationSeconds));
        sb.append("}");
    }

    private static void appendTargetJson(StringBuilder sb, TargetVisibilitySummary target, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"targetId\": \"").append(jsonEscape(target.targetId)).append("\",\n");
        sb.append(indent).append("  \"targetName\": \"").append(jsonEscape(target.targetName)).append("\",\n");
        sb.append(indent).append("  \"csvPath\": \"").append(jsonEscape(target.csvPath)).append("\",\n");
        sb.append(indent).append("  \"baseLaunchUTC\": \"").append(jsonEscape(target.baseLaunchUTC)).append("\",\n");
        sb.append(indent).append("  \"launchUTC\": \"").append(jsonEscape(target.launchUTC)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(target.payloadType)).append("\",\n");
        sb.append(indent).append("  \"launchTimeJ2000Sec\": ").append(formatJsonNumber(target.launchTimeJ2000Sec)).append(",\n");
        sb.append(indent).append("  \"finalVisibleWindow\": ");
        appendTimeWindowJson(sb, finalVisibleWindowFromWindows(target.visibleWindows));
        sb.append(",\n");
        sb.append(indent).append("  \"visibleWindows\": [\n");
        for (int i = 0; i < target.visibleWindows.size(); i++) {
            if (i > 0) sb.append(",\n");
            appendWindowSummaryJson(sb, target.visibleWindows.get(i), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendWindowSummaryJson(StringBuilder sb, VisibleWindowSummary window, String indent) {
        sb.append(indent).append("{");
        sb.append("\"satelliteId\": \"").append(jsonEscape(window.satelliteId)).append("\", ");
        sb.append("\"satelliteName\": \"").append(jsonEscape(window.satelliteName)).append("\", ");
        sb.append("\"satelliteIndex\": ").append(window.satelliteIndex).append(", ");
        sb.append("\"arcIndex\": ").append(window.arcIndex).append(", ");
        sb.append("\"payloadId\": \"").append(jsonEscape(window.payloadId)).append("\", ");
        sb.append("\"payloadName\": \"").append(jsonEscape(window.payloadName)).append("\", ");
        sb.append("\"payloadType\": \"").append(jsonEscape(window.payloadType)).append("\", ");
        sb.append("\"startTime\": \"").append(jsonEscape(window.startUtc)).append("\", ");
        sb.append("\"endTime\": \"").append(jsonEscape(window.endUtc)).append("\", ");
        sb.append("\"startJ2000Sec\": ").append(formatJsonNumber(window.startJ2000Sec)).append(", ");
        sb.append("\"endJ2000Sec\": ").append(formatJsonNumber(window.endJ2000Sec)).append(", ");
        sb.append("\"durationSeconds\": ").append(formatJsonNumber(window.durationSeconds)).append(", ");
        sb.append("\"pointCount\": ").append(window.pointCount);
        sb.append("}");
    }

    /** 按载荷输出同一卫星平台下的全部目标可见弧段。 */
    private static void appendSatellitePayloadJson(StringBuilder sb, List<SatelliteVisibilityResult> arcs,
            String indent) {
        SatelliteVisibilityResult first = arcs.get(0);
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"payloadId\": \"").append(jsonEscape(first.payloadId)).append("\",\n");
        sb.append(indent).append("  \"payloadName\": \"").append(jsonEscape(first.payloadName)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(first.payloadType)).append("\",\n");
        sb.append(indent).append("  \"visibilityConstraint\": ");
        appendVisibilityConstraintJson(sb, first.visibilityConstraint);
        sb.append(",\n");
        sb.append(indent).append("  \"finalVisibleWindow\": ");
        appendTimeWindowJson(sb, finalVisibleWindow(arcs));
        sb.append(",\n");
        sb.append(indent).append("  \"targets\": [\n");
        List<List<SatelliteVisibilityResult>> targetGroups = groupArcsByTarget(arcs);
        for (int ti = 0; ti < targetGroups.size(); ti++) {
            if (ti > 0) sb.append(",\n");
            appendSatelliteTargetJson(sb, targetGroups.get(ti), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static List<List<SatelliteVisibilityResult>> groupArcsByTarget(List<SatelliteVisibilityResult> arcs) {
        return groupArcs(arcs, arc -> nullToEmpty(arc.targetId) + "\u0001" + nullToEmpty(arc.targetName));
    }

    private static void appendSatelliteTargetJson(StringBuilder sb, List<SatelliteVisibilityResult> arcs,
            String indent) {
        SatelliteVisibilityResult first = arcs.get(0);
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"targetId\": \"").append(jsonEscape(first.targetId)).append("\",\n");
        sb.append(indent).append("  \"targetName\": \"").append(jsonEscape(first.targetName)).append("\",\n");
        sb.append(indent).append("  \"payloadId\": \"").append(jsonEscape(first.payloadId)).append("\",\n");
        sb.append(indent).append("  \"payloadName\": \"").append(jsonEscape(first.payloadName)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(first.payloadType)).append("\",\n");
        sb.append(indent).append("  \"visibleArcs\": [\n");
        for (int ai = 0; ai < arcs.size(); ai++) {
            if (ai > 0) sb.append(",\n");
            appendVisibleArcJson(sb, arcs.get(ai), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendVisibleArcJson(StringBuilder sb, SatelliteVisibilityResult arc, String indent) {
        VisibleWindowSummary summary = VisibleWindowSummary.fromArc(arc);
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"arcIndex\": ").append(arc.arcIndex).append(",\n");
        sb.append(indent).append("  \"payloadId\": \"").append(jsonEscape(arc.payloadId)).append("\",\n");
        sb.append(indent).append("  \"payloadName\": \"").append(jsonEscape(arc.payloadName)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(arc.payloadType)).append("\",\n");
        sb.append(indent).append("  \"startTime\": \"").append(jsonEscape(summary.startUtc)).append("\",\n");
        sb.append(indent).append("  \"endTime\": \"").append(jsonEscape(summary.endUtc)).append("\",\n");
        sb.append(indent).append("  \"startJ2000Sec\": ").append(formatJsonNumber(summary.startJ2000Sec)).append(",\n");
        sb.append(indent).append("  \"endJ2000Sec\": ").append(formatJsonNumber(summary.endJ2000Sec)).append(",\n");
        sb.append(indent).append("  \"durationSeconds\": ").append(formatJsonNumber(summary.durationSeconds)).append(",\n");
        sb.append(indent).append("  \"pointCount\": ").append(summary.pointCount).append(",\n");
        sb.append(indent).append("  \"trajectoryPoints\": [\n");
        for (int pi = 0; pi < arc.trajectoryPoints.size(); pi++) {
            if (pi > 0) sb.append(",\n");
            appendTrajectoryPointJson(sb, arc.trajectoryPoints.get(pi), arc, indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendTrajectoryPointJson(StringBuilder sb, VisibleTrajectoryPoint point,
            SatelliteVisibilityResult arc, String indent) {
        sb.append(indent).append("{");
        sb.append("\"longitudeDeg\": ").append(formatJsonNumber(point.longitudeDeg)).append(", ");
        sb.append("\"latitudeDeg\": ").append(formatJsonNumber(point.latitudeDeg)).append(", ");
        sb.append("\"heightM\": ").append(formatJsonNumber(point.heightM)).append(", ");
        sb.append("\"absoluteTimeJ2000Sec\": ").append(formatJsonNumber(point.absoluteTimeSec)).append(", ");
        sb.append("\"absoluteTimeUtc\": \"").append(j2000ToUtcMillis(point.absoluteTimeSec)).append("\", ");
        sb.append("\"relativeTimeSec\": ").append(formatJsonNumber(point.relativeTimeSec)).append(", ");
        sb.append("\"sourceTrajectoryUtc\": \"")
                .append(j2000ToUtcMillis(sourceReferenceFor(arc) + point.relativeTimeSec))
                .append("\"");
        sb.append("}");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatJsonNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return String.format(Locale.US, "%.9f", value);
    }
    /** 中文兼容格式保留原字段及时间文本，计算与英文输出读取同一结果对象。 */
    static String toChineseJson(LaunchOffsetCalcResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("  {\n");
        sb.append("    \"目标发射时间\": \"").append(result.launchTimeUtc).append("\",\n");
        sb.append("    \"发射偏移量(s)\": ").append(result.offsetSecond).append(",\n");
        sb.append("    \"可见卫星\": [\n");
        for (int si = 0; si < result.visibleSatellites.size(); si++) {
            if (si > 0) sb.append(",\n");
            SatelliteVisibilityResult svr = result.visibleSatellites.get(si);
            sb.append("      {\n");
            sb.append("        \"卫星代号\": \"").append(jsonEscape(svr.satelliteName)).append("\",\n");
            sb.append("        \"卫星编号\": ").append(svr.satelliteIndex).append(",\n");
            sb.append("        \"目标id\": \"").append(jsonEscape(svr.targetId)).append("\",\n");
            sb.append("        \"可见弧段\": [\n");
            sb.append("          {\n");
            sb.append("            \"弧段序号\": ").append(svr.arcIndex).append(",\n");
            sb.append("            \"轨迹点集合\": [\n");
            for (int pi = 0; pi < svr.trajectoryPoints.size(); pi++) {
                if (pi > 0) sb.append(",\n");
                VisibleTrajectoryPoint tp = svr.trajectoryPoints.get(pi);
                String currentTime = j2000ToUtc(tp.absoluteTimeSec);
                String sourceTime = j2000ToUtc(sourceReferenceFor(svr, result) + tp.relativeTimeSec);
                sb.append("              {");
                sb.append("\"经度\": ").append(tp.longitudeDeg).append(", ");
                sb.append("\"纬度\": ").append(tp.latitudeDeg).append(", ");
                sb.append("\"高度\": ").append(tp.heightM).append(", ");
                sb.append("\"当前轨迹点时间\": \"").append(currentTime).append("\", ");
                sb.append("\"对应的输入的轨迹文件的轨迹点时间\": \"").append(sourceTime).append("\"");
                sb.append("}");
            }
            sb.append("\n            ]\n");
            sb.append("          }\n");
            sb.append("        ]\n");
            sb.append("      }");
        }
        sb.append("\n    ]\n");
        sb.append("  }");
        return sb.toString();
    }

    /** 按首次出现顺序分组；三种键沿用既有字段及分隔符，不改变空标识或同名对象的归并规则。 */
    private static List<List<SatelliteVisibilityResult>> groupArcs(List<SatelliteVisibilityResult> arcs,
            java.util.function.Function<SatelliteVisibilityResult, String> identity) {
        Map<String, List<SatelliteVisibilityResult>> grouped = new LinkedHashMap<>();
        for (SatelliteVisibilityResult arc : arcs) {
            grouped.computeIfAbsent(identity.apply(arc), key -> new ArrayList<>()).add(arc);
        }
        return new ArrayList<>(grouped.values());
    }
}
