package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;

public final class VisibilityTarget {

    private final String targetId;
    private final OrbitStateProvider orbit;

    public VisibilityTarget(String targetId, OrbitStateProvider orbit) {
        this.targetId = targetId;
        this.orbit = orbit;
    }

    public String getTargetId() { return targetId; }
    public OrbitStateProvider getOrbit() { return orbit; }
}
