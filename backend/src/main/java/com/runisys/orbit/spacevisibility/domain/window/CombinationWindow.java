package com.runisys.orbit.spacevisibility.domain.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CombinationWindow {

    private final List<String> satelliteIds;
    private final TimeInterval interval;

    public CombinationWindow(List<String> satelliteIds, TimeInterval interval) {
        this.satelliteIds = Collections.unmodifiableList(new ArrayList<String>(satelliteIds));
        this.interval = interval;
    }

    public List<String> getSatelliteIds() {
        return satelliteIds;
    }

    public TimeInterval getInterval() {
        return interval;
    }
}
