package com.runisys.orbit.spacevisibility.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表示指定我方卫星组合对同一空间目标的有效观测弧段。
 */
public final class VisibilityObservationResult {

    private final String targetId;
    private final String targetName;
    private final List<String> observerSatelliteIds;
    private final List<String> observerSatelliteNames;
    private final List<VisibilityArc> arcs;
    private final String observationType;
    private final String observationTypeName;
    /** WinTool 简约格式的观测摘要（任务摘要 + 各单星观测时段），仅供展示。 */
    private String conciseSummary;
    /** 目标 NORAD 编号（供 WinTool 格式摘要与对外对账使用）；目录缺失时为 null。 */
    private String targetNorad;
    /** 目标质量（公斤）；目录缺失时为 null。 */
    private Double targetWeightKg;
    /** 目标两行根数第一行；非 TLE_SGP4 目标为 null。 */
    private String firstOrbitLine;
    /** 目标两行根数第二行；非 TLE_SGP4 目标为 null。 */
    private String secondOrbitLine;

    /**
     * 创建一个目标观测筛选结果。
     *
     * @param targetId 当前被观测的空间目标标识
     * @param observerSatelliteIds 参与本条观测结果的我方卫星标识
     * @param arcs 满足最小可见时长的观测弧段
     */
    public VisibilityObservationResult(String targetId, List<String> observerSatelliteIds, List<VisibilityArc> arcs) {
        this(targetId, targetId, observerSatelliteIds, observerSatelliteIds, arcs);
    }

    /**
     * 创建供接口返回的观测结果，同时保留稳定的内部标识和可直接展示的名称。
     *
     * @param targetId 目标内部标识
     * @param targetName 目标名称
     * @param observerSatelliteIds 参与观测的我方卫星内部标识
     * @param observerSatelliteNames 与卫星标识同顺序的我方卫星名称
     * @param arcs 满足约束的观测弧段
     */
    public VisibilityObservationResult(String targetId,
                                       String targetName,
                                       List<String> observerSatelliteIds,
                                       List<String> observerSatelliteNames,
                                       List<VisibilityArc> arcs) {
        if (targetId == null || targetName == null || observerSatelliteIds == null || observerSatelliteNames == null
                || observerSatelliteIds.size() != observerSatelliteNames.size() || arcs == null) {
            throw new IllegalArgumentException("观测结果展示参数无效");
        }
        this.targetId = targetId;
        this.targetName = targetName;
        this.observerSatelliteIds = Collections.unmodifiableList(new ArrayList<String>(observerSatelliteIds));
        this.observerSatelliteNames = Collections.unmodifiableList(new ArrayList<String>(observerSatelliteNames));
        this.arcs = Collections.unmodifiableList(new ArrayList<VisibilityArc>(arcs));
        this.observationType = observerSatelliteIds.size() == 1 ? "SINGLE" : "COOPERATIVE";
        this.observationTypeName = observerSatelliteIds.size() == 1 ? "单星观测" : "共同观测";
    }

    public String getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public List<String> getObserverSatelliteIds() { return observerSatelliteIds; }
    public List<String> getObserverSatelliteNames() { return observerSatelliteNames; }
    public List<VisibilityArc> getArcs() { return arcs; }
    public String getObservationType() { return observationType; }
    public String getObservationTypeName() { return observationTypeName; }

    public String getConciseSummary() { return conciseSummary; }

    public void setConciseSummary(String conciseSummary) { this.conciseSummary = conciseSummary; }

    /**
     * 补充目标目录元数据（NORAD、重量、两行根数），供 WinTool 格式摘要渲染。
     * 门面在结果可读化时调用一次；目录字段缺失时对应入参传 null。
     */
    public void setTargetMeta(String targetNorad, Double targetWeightKg,
                              String firstOrbitLine, String secondOrbitLine) {
        this.targetNorad = targetNorad;
        this.targetWeightKg = targetWeightKg;
        this.firstOrbitLine = firstOrbitLine;
        this.secondOrbitLine = secondOrbitLine;
    }

    /** @return 目标 NORAD 编号；目录缺失时为 null。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getTargetNorad() { return targetNorad; }

    /** @return 目标质量（公斤）；目录缺失时为 null。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public Double getTargetWeightKg() { return targetWeightKg; }

    /** @return 目标两行根数第一行；非 TLE 目标为 null。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getFirstOrbitLine() { return firstOrbitLine; }

    /** @return 目标两行根数第二行；非 TLE 目标为 null。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getSecondOrbitLine() { return secondOrbitLine; }
}
