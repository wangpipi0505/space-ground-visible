package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.downlink.IlluminationRequirement;
import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationCoordinate;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;

import java.time.Instant;

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
