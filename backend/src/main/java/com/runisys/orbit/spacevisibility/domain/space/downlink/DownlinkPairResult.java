package com.runisys.orbit.spacevisibility.domain.space.downlink;

import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单条站星链路的UTC扫描窗口结果；ID保持原类型，窗口列表复制并只读，避免调用者改写汇总。
 */
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
