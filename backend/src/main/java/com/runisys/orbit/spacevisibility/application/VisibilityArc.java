package com.runisys.orbit.spacevisibility.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.runisys.orbit.spacevisibility.domain.payload.IlluminationState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 某一光照状态下的可见弧段数据：时间为UTC Instant，时长单位秒。
 * 该对象描述单段，组合与累计时长由规划器处理，不把不连续弧段自动并为连续窗口。
 */
public final class VisibilityArc {

    private final Instant startTime;
    private final Instant endTime;
    private final List<ObserverIllumination> observerIlluminations;
    private final TargetIllumination targetIllumination;
    /**
     * 父窗口内部因光照状态变化产生的稳定片段。父窗口才是组合时长约束的判定单位，
     * 此字段只用于保留状态变化明细，不能被误展示为独立的合格观测窗口。
     */
    private final List<IlluminationSegment> illuminationSegments;
    /**
     * 组合父窗口对应的各星原始连续单星窗口。该字段与父窗口不同：父窗口是各星求交后的共同时间，
     * 此处保留各星各自完整的连续可观测范围，供页面解释组合起止边界的来源。
     */
    private final List<SatelliteSegment> satelliteSegments;
    /**
     * 单星约束弧段的观测统计（距离/方位/俯仰/角速度极值）。仅当组合成员只有一颗星时填充，
     * 多星组合的统计分别挂在其 satelliteSegments 上；旧数据为 null。
     */
    private final VisibilityPairStatistics statistics;

    public VisibilityArc(Instant startTime, Instant endTime) {
        this(startTime, endTime, Collections.<ObserverIllumination>emptyList(), null,
                Collections.<IlluminationSegment>emptyList(), Collections.<SatelliteSegment>emptyList());
    }

    /**
     * 创建一个光照状态在整个弧段内保持稳定的可见观测弧段。
     *
     * @param startTime 弧段开始时刻
     * @param endTime 弧段结束时刻
     * @param observerIlluminations 参与观测的卫星精确光照状态
     * @param targetIllumination 当前目标的精确光照状态
     */
    public VisibilityArc(Instant startTime,
                         Instant endTime,
                         List<ObserverIllumination> observerIlluminations,
                         TargetIllumination targetIllumination) {
        this(startTime, endTime, observerIlluminations, targetIllumination,
                Collections.<IlluminationSegment>emptyList(), Collections.<SatelliteSegment>emptyList());
    }

    /**
     * 创建一个满足组合时长约束的父观测窗口，并可附带其内部精确光照变化片段。
     *
     * @param illuminationSegments 按时间升序排列的光照稳定片段；为空表示整段状态稳定或旧数据
     */
    public VisibilityArc(Instant startTime,
                         Instant endTime,
                         List<ObserverIllumination> observerIlluminations,
                         TargetIllumination targetIllumination,
                         List<IlluminationSegment> illuminationSegments) {
        this(startTime, endTime, observerIlluminations, targetIllumination, illuminationSegments,
                Collections.<SatelliteSegment>emptyList());
    }

    /** 创建父窗口及其已解析的原始单星窗口；仅组合规划器在确定交集后调用。 */
    private VisibilityArc(Instant startTime,
                          Instant endTime,
                          List<ObserverIllumination> observerIlluminations,
                          TargetIllumination targetIllumination,
                          List<IlluminationSegment> illuminationSegments,
                          List<SatelliteSegment> satelliteSegments) {
        this(startTime, endTime, observerIlluminations, targetIllumination, illuminationSegments,
                satelliteSegments, null);
    }

    /** 完整构造：含单星约束弧段的观测统计。 */
    private VisibilityArc(Instant startTime,
                          Instant endTime,
                          List<ObserverIllumination> observerIlluminations,
                          TargetIllumination targetIllumination,
                          List<IlluminationSegment> illuminationSegments,
                          List<SatelliteSegment> satelliteSegments,
                          VisibilityPairStatistics statistics) {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime) || observerIlluminations == null
                || illuminationSegments == null || satelliteSegments == null) {
            throw new IllegalArgumentException("观测弧段起止时间或光照状态无效");
        }
        this.startTime = startTime;
        this.endTime = endTime;
        this.observerIlluminations = Collections.unmodifiableList(new ArrayList<ObserverIllumination>(observerIlluminations));
        this.targetIllumination = targetIllumination;
        this.illuminationSegments = Collections.unmodifiableList(new ArrayList<IlluminationSegment>(illuminationSegments));
        this.satelliteSegments = Collections.unmodifiableList(new ArrayList<SatelliteSegment>(satelliteSegments));
        this.statistics = statistics;
    }

    /** @return 以 UTC 三位毫秒序列化的观测弧段开始时刻。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getStartTime() { return startTime; }

    /** @return 以 UTC 三位毫秒序列化的观测弧段结束时刻。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getEndTime() { return endTime; }
    public double getDurationSec() {
        return endTime.getEpochSecond() - startTime.getEpochSecond()
                + (endTime.getNano() - startTime.getNano()) / 1_000_000_000.0;
    }

    /** @return 与参与卫星顺序一致的观测星精确光照状态。 */
    public List<ObserverIllumination> getObserverIlluminations() {
        return observerIlluminations;
    }

    /**
     * 组合弧段内每颗参与卫星的原始连续单星观测时段。父窗口由这些时段求交得到，
     * 因而各星起止通常不同；≥2 颗星的组合弧段才输出该字段，单星弧段省略。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<SatelliteSegment> getSatelliteSegments() {
        if (observerIlluminations.size() < 2) {
            return Collections.emptyList();
        }
        if (!satelliteSegments.isEmpty()) {
            return satelliteSegments;
        }
        // 兼容由旧调用方直接构造的组合弧段：缺少原始窗口时，仍返回父窗口范围而不改变旧接口形状。
        List<SatelliteSegment> segments = new ArrayList<SatelliteSegment>();
        for (ObserverIllumination observer : observerIlluminations) {
            segments.add(new SatelliteSegment(observer.getSatelliteId(), observer.getSatelliteName(),
                    observer.getState(), observer.getStateName(), startTime, endTime, getDurationSec()));
        }
        return segments;
    }

    /** @return 当前目标的精确光照状态；兼容旧弧段时为 null。 */
    public TargetIllumination getTargetIllumination() {
        return targetIllumination;
    }

    /**
     * @return 父窗口内按光照状态切分的明细；主结果列表必须使用父窗口自身的起止时间和时长
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<IlluminationSegment> getIlluminationSegments() {
        return illuminationSegments;
    }

    /**
     * 用可读名称替换光照详情中的临时标识，同时保持时段和精确光照状态不变。
     *
     * @param observers 已补全名称的观测星光照信息
     * @param target 已补全名称的目标光照信息
     * @return 新的不可变弧段
     */
    public VisibilityArc withIlluminations(List<ObserverIllumination> observers, TargetIllumination target) {
        return withIlluminations(observers, target, illuminationSegments);
    }

    /** 使用可读名称替换父窗口及其光照明细的状态实体。 */
    public VisibilityArc withIlluminations(List<ObserverIllumination> observers,
                                           TargetIllumination target,
                                           List<IlluminationSegment> segments) {
        return new VisibilityArc(startTime, endTime, observers, target, segments, satelliteSegments);
    }

    /** 保持父窗口和光照状态不变，附加用于展示的原始单星连续窗口。 */
    public VisibilityArc withSatelliteSegments(List<SatelliteSegment> segments) {
        return new VisibilityArc(startTime, endTime, observerIlluminations, targetIllumination,
                illuminationSegments, segments, statistics);
    }

    /** 保持父窗口不变，附加单星约束弧段的观测统计（仅单星组合使用）。 */
    public VisibilityArc withPairStatistics(VisibilityPairStatistics statistics) {
        return new VisibilityArc(startTime, endTime, observerIlluminations, targetIllumination,
                illuminationSegments, satelliteSegments, statistics);
    }

    /**
     * @return 单星约束弧段的观测统计（距离范围/方位范围/俯仰范围/最大视线角速度）；
     * 多星组合的统计在各 satelliteSegments 上，单星弧段之外的场景为 null。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public VisibilityPairStatistics getStatistics() {
        return statistics;
    }

    /** 表示单颗参与观测卫星在一段稳定弧段内的精确光照状态。 */
    public static final class ObserverIllumination {
        private final String satelliteId;
        private final String satelliteName;
        private final String state;
        private final String stateName;

        public ObserverIllumination(String satelliteId, String satelliteName, IlluminationState state) {
            if (satelliteId == null || satelliteName == null || state == null) {
                throw new IllegalArgumentException("观测星光照详情无效");
            }
            this.satelliteId = satelliteId;
            this.satelliteName = satelliteName;
            this.state = state.name();
            this.stateName = stateName(state);
        }

        public String getSatelliteId() { return satelliteId; }
        public String getSatelliteName() { return satelliteName; }
        public String getState() { return state; }
        public String getStateName() { return stateName; }
    }

    /** 表示目标卫星在一段稳定弧段内的精确光照状态。 */
    public static final class TargetIllumination {
        private final String targetId;
        private final String targetName;
        private final String state;
        private final String stateName;

        public TargetIllumination(String targetId, String targetName, IlluminationState state) {
            if (targetId == null || targetName == null || state == null) {
                throw new IllegalArgumentException("目标光照详情无效");
            }
            this.targetId = targetId;
            this.targetName = targetName;
            this.state = state.name();
            this.stateName = stateName(state);
        }

        public String getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public String getState() { return state; }
        public String getStateName() { return stateName; }
    }

    /** 父观测窗口内部的一段光照稳定明细，起止时间始终落在父窗口范围内。 */
    public static final class IlluminationSegment {
        private final Instant startTime;
        private final Instant endTime;
        private final List<ObserverIllumination> observerIlluminations;
        private final TargetIllumination targetIllumination;

        public IlluminationSegment(Instant startTime,
                                   Instant endTime,
                                   List<ObserverIllumination> observerIlluminations,
                                   TargetIllumination targetIllumination) {
            if (startTime == null || endTime == null || !endTime.isAfter(startTime) || observerIlluminations == null) {
                throw new IllegalArgumentException("光照明细起止时间或状态无效");
            }
            this.startTime = startTime;
            this.endTime = endTime;
            this.observerIlluminations = Collections.unmodifiableList(
                    new ArrayList<ObserverIllumination>(observerIlluminations));
            this.targetIllumination = targetIllumination;
        }

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getStartTime() { return startTime; }

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getEndTime() { return endTime; }

        public double getDurationSec() {
            return endTime.getEpochSecond() - startTime.getEpochSecond()
                    + (endTime.getNano() - startTime.getNano()) / 1_000_000_000.0;
        }

        public List<ObserverIllumination> getObserverIlluminations() { return observerIlluminations; }
        public TargetIllumination getTargetIllumination() { return targetIllumination; }
    }

    /** 组合弧段展开展示用的单颗卫星独立观测时段。 */
    public static final class SatelliteSegment {
        private final String satelliteId;
        private final String satelliteName;
        private final String state;
        private final String stateName;
        private final Instant startTime;
        private final Instant endTime;
        private final double durationSec;
        /** 该星覆盖本时段的原始观测统计（距离/方位/俯仰/角速度极值）；旧数据为 null。 */
        private final VisibilityPairStatistics statistics;

        public SatelliteSegment(String satelliteId, String satelliteName, String state, String stateName,
                                Instant startTime, Instant endTime, double durationSec) {
            this(satelliteId, satelliteName, state, stateName, startTime, endTime, durationSec, null);
        }

        /** 创建携带观测统计的单星时段；统计来自覆盖本时段的原始“星-目标”组合。 */
        public SatelliteSegment(String satelliteId, String satelliteName, String state, String stateName,
                                Instant startTime, Instant endTime, double durationSec,
                                VisibilityPairStatistics statistics) {
            this.satelliteId = satelliteId;
            this.satelliteName = satelliteName;
            this.state = state;
            this.stateName = stateName;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationSec = durationSec;
            this.statistics = statistics;
        }

        public String getSatelliteId() { return satelliteId; }
        public String getSatelliteName() { return satelliteName; }
        public String getState() { return state; }
        public String getStateName() { return stateName; }

        /** @return 以 UTC 三位毫秒序列化的该星观测开始时刻。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getStartTime() { return startTime; }

        /** @return 以 UTC 三位毫秒序列化的该星观测结束时刻。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getEndTime() { return endTime; }
        public double getDurationSec() { return durationSec; }

        /** @return 该星观测统计（距离范围/方位范围/俯仰范围/最大视线角速度）；旧数据为 null。 */
        public VisibilityPairStatistics getStatistics() { return statistics; }
    }

    /** 将精确状态转换为接口和页面使用的中文名称。 */
    private static String stateName(IlluminationState state) {
        switch (state) {
            case SUNLIT:
                return "阳照";
            case PENUMBRA:
                return "半影";
            case UMBRA:
                return "本影";
            default:
                throw new IllegalArgumentException("未知光照状态: " + state);
        }
    }
}
