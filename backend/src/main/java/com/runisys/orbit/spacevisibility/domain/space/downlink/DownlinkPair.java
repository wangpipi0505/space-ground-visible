package com.runisys.orbit.spacevisibility.domain.space.downlink;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationCoordinate;
import java.time.Instant;

/**
 * 一次卫星—站点下传扫描的不可变参数；UTC区间、步长/保护/断链值均以秒计，仰角以度计。
 * 轨道provider与站点坐标已由应用层解析，构造器保持旧有效性检查，各访问器仅读取原值。
 */
public final class DownlinkPair {

    private final Long satelliteId;
    private final String stationId;
    private final OrbitStateProvider orbit;
    private final GroundStationCoordinate station;
    private final Instant startTime;
    private final Instant endTime;
    private final double coarseStepSec;
    private final double fineStepSec;
    private final double minElevationDeg;
    private final double maxElevationDeg;
    private final double preGuardSec;
    private final double postGuardSec;
    private final double allowedGapSec;
    private final IlluminationRequirement illuminationRequirement;

    public DownlinkPair(Long satelliteId,
                        String stationId,
                        OrbitStateProvider orbit,
                        GroundStationCoordinate station,
                        Instant startTime,
                        Instant endTime,
                        double coarseStepSec,
                        double fineStepSec,
                        double minElevationDeg,
                        double maxElevationDeg,
                        double preGuardSec,
                        double postGuardSec,
                        double allowedGapSec,
                        IlluminationRequirement illuminationRequirement) {
        if (satelliteId == null || stationId == null || stationId.trim().isEmpty() || orbit == null || station == null
                || startTime == null || endTime == null || illuminationRequirement == null) {
            throw new IllegalArgumentException("下传规划任务参数无效");
        }
        this.satelliteId = satelliteId;
        this.stationId = stationId;
        this.orbit = orbit;
        this.station = station;
        this.startTime = startTime;
        this.endTime = endTime;
        this.coarseStepSec = coarseStepSec;
        this.fineStepSec = fineStepSec;
        this.minElevationDeg = minElevationDeg;
        this.maxElevationDeg = maxElevationDeg;
        this.preGuardSec = preGuardSec;
        this.postGuardSec = postGuardSec;
        this.allowedGapSec = allowedGapSec;
        this.illuminationRequirement = illuminationRequirement;
    }

    public Long getSatelliteId() { return satelliteId; }
    public String getStationId() { return stationId; }
    public OrbitStateProvider getOrbit() { return orbit; }
    public GroundStationCoordinate getStation() { return station; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public double getCoarseStepSec() { return coarseStepSec; }
    public double getFineStepSec() { return fineStepSec; }
    public double getMinElevationDeg() { return minElevationDeg; }
    public double getMaxElevationDeg() { return maxElevationDeg; }
    public double getPreGuardSec() { return preGuardSec; }
    public double getPostGuardSec() { return postGuardSec; }
    public double getAllowedGapSec() { return allowedGapSec; }
    public IlluminationRequirement getIlluminationRequirement() { return illuminationRequirement; }
}
