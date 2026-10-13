package com.runisys.orbit.spacevisibility.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VisibilityPairResult {

    private final String observerSatelliteId;
    private final String targetId;
    private final List<VisibilityArc> arcs;
    /** 全部可见采样点的距离/方位/俯仰/角速度统计；无可见采样或旧调用方为 null。 */
    private final VisibilityPairStatistics statistics;

    public VisibilityPairResult(String observerSatelliteId, String targetId, List<VisibilityArc> arcs) {
        this(observerSatelliteId, targetId, arcs, null);
    }

    /** 创建携带观测统计的组合结果；统计由规划服务在可见采样点上累计。 */
    public VisibilityPairResult(String observerSatelliteId, String targetId, List<VisibilityArc> arcs,
                                VisibilityPairStatistics statistics) {
        this.observerSatelliteId = observerSatelliteId;
        this.targetId = targetId;
        this.arcs = Collections.unmodifiableList(new ArrayList<VisibilityArc>(arcs));
        this.statistics = statistics;
    }

    public String getObserverSatelliteId() { return observerSatelliteId; }
    public String getTargetId() { return targetId; }
    public List<VisibilityArc> getArcs() { return arcs; }
    /** @return 该组合在规划时段内的观测统计；无可见采样或旧数据为 null。 */
    public VisibilityPairStatistics getStatistics() { return statistics; }
}
