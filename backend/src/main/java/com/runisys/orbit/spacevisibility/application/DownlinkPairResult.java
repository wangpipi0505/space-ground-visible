package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DownlinkPairResult {

    private final Long satelliteId;
    private final String stationId;
    private final List<TimeInterval> windows;

    public DownlinkPairResult(Long satelliteId, String stationId, List<TimeInterval> windows) {
        this.satelliteId = satelliteId;
        this.stationId = stationId;
        this.windows = Collections.unmodifiableList(new ArrayList<TimeInterval>(windows));
    }

    public Long getSatelliteId() { return satelliteId; }
    public String getStationId() { return stationId; }
    public List<TimeInterval> getWindows() { return windows; }
}
